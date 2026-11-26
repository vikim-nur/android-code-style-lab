(function(root,factory){
  const api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  else root.DetektBallot=api;
})(globalThis,function(){
  'use strict';
  const marker=s=>'<!-- detekt-ballot:'+s.id+' -->';
  const prefix=s=>'[detekt-ballot:'+s.id+']';
  function validate(s,v,complete=true){
    if(!v||v.schema!==1||v.survey!==s.id||v.version!==s.version)throw Error('Ответ относится к другой версии анкеты.');
    if(typeof v.name!=='string'||!v.name.trim()||v.name.trim().length>80||/[\u0000-\u001f]/.test(v.name))throw Error('Укажи имя (до 80 символов, одной строкой).');
    if(typeof v.comment!=='string'||v.comment.length>1000)throw Error('Общий комментарий — до 1000 символов.');
    if(!v.answers||typeof v.answers!=='object'||Array.isArray(v.answers))throw Error('Некорректные ответы.');
    const ids=new Set(s.questions.map(q=>q.id));
    if(Object.keys(v.answers).some(id=>!ids.has(id)))throw Error('Неизвестный вопрос.');
    const comments=v.comments??{};
    if(typeof comments!=='object'||Array.isArray(comments)||comments===null||Object.keys(comments).some(id=>!ids.has(id)))throw Error('Некорректные комментарии.');
    const answers={},cleanComments={},settings={};
    const rawSettings=v.settings??{};
    if(typeof rawSettings!=='object'||rawSettings===null||Array.isArray(rawSettings)||Object.keys(rawSettings).some(id=>!ids.has(id)))throw Error('Некорректные настройки.');
    for(const q of s.questions){
      const a=v.answers[q.id]??null;
      if(a!==null&&(!Number.isInteger(a)||a<0||a>=q.options.length))throw Error('Некорректный выбор: '+q.id);
      if(complete&&a===null)throw Error('Ответь на каждый вопрос: включить или выключить.');
      answers[q.id]=a;
      if(a===0){
        const config=rawSettings[q.id]??{};
        if(typeof config!=='object'||config===null||Array.isArray(config)||Object.keys(config).some(key=>!(q.settings||[]).some(f=>f.key===key)))throw Error('Неизвестный параметр: '+q.id);
        settings[q.id]={};
        for(const f of q.settings||[]){
          const value=config[f.key];
          if(value===undefined||value===null||value===''){
            if(complete)throw Error('Выбери параметр '+f.key+' для '+q.id);
            continue;
          }
          if(typeof value!=='string'||value.length>1000||!value.trim()||(f.values&&!f.values.includes(value)))throw Error('Некорректный параметр '+f.key);
          if(f.kind==='list'){
            let list;try{list=JSON.parse(value);}catch{throw Error('Нужен JSON-массив: '+f.key);}
            if(!Array.isArray(list)||list.some(x=>typeof x!=='string'||!x.trim()))throw Error('Нужен массив имён: '+f.key);
          }
          settings[q.id][f.key]=value;
        }
      }
      const c=comments[q.id]??'';
      if(typeof c!=='string'||c.length>500)throw Error('Комментарий к вопросу — до 500 символов.');
      if(c.trim())cleanComments[q.id]=c;
    }
    const result={schema:1,survey:s.id,version:s.version,name:v.name.trim(),answers,settings,comments:cleanComments,comment:v.comment};
    if(JSON.stringify(result).length>50000)throw Error('Ответ слишком большой: сократи комментарии.');
    return result;
  }
  function issueBody(s,v){
    const data=validate(s,v);
    return ['Ответы по Detekt и Compose Rules. Это мнение участника, не утверждение правил.',
      '[Анкета]('+s.pageUrl+') · [Результаты]('+s.pageUrl+'results.html?autoload=1)',
      'Выборы записаны по ID вопроса; номер варианта в JSON начинается с 0.',
      marker(s),'```json',JSON.stringify(data),'```'].join('\n');
  }
  function issueUrl(s,v){
    const data=validate(s,v);
    const url='https://github.com/'+s.repository+'/issues/new?title='+encodeURIComponent(prefix(s)+' '+data.name)+'&body='+encodeURIComponent(issueBody(s,data));
    return url.length<=7500?url:null;
  }
  function readIssue(s,i){
    const body=typeof i.body==='string'?i.body:'';
    if(body.split(/\r?\n/).filter(x=>x===marker(s)).length!==1)throw Error('Нет единственного блока этой анкеты.');
    const tail=body.slice(body.indexOf(marker(s))+marker(s).length);
    const blocks=[...tail.matchAll(/```json\r?\n([\s\S]*?)\r?\n```/g)];
    if(blocks.length!==1)throw Error('Нужен один блок ответа.');
    return validate(s,JSON.parse(blocks[0][1]));
  }
  function aggregate(s,issues){
    const latest=new Map(),rejected=[];
    for(const i of issues){
      if(!i||i.pull_request||i.user?.type==='Bot')continue;
      const body=typeof i.body==='string'?i.body:'';
      if(!i.title?.startsWith(prefix(s))&&!body.includes(marker(s)))continue;
      if(!Number.isSafeInteger(i.number)||i.number<1||typeof i.user?.login!=='string'||!/^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}$/i.test(i.user.login)){
        rejected.push({number:i.number,reason:'Некорректный номер или автор.'});continue;
      }
      const identity=Number.isSafeInteger(i.user.id)?'id:'+i.user.id:'login:'+i.user.login.toLowerCase();
      if(!latest.has(identity)||latest.get(identity).number<i.number)latest.set(identity,i);
    }
    const participants=[];
    for(const i of latest.values()){
      try{participants.push({...readIssue(s,i),login:i.user.login,number:i.number,
        issueUrl:'https://github.com/'+s.repository+'/issues/'+i.number});}
      catch(e){rejected.push({number:i.number,reason:e.message});}
    }
    participants.sort((a,b)=>a.name.localeCompare(b.name,'ru'));
    return {participants,rejected,questions:s.questions.map(q=>({id:q.id,title:q.title,
      settings:(q.settings||[]).map(f=>({key:f.key,label:f.label,values:[...new Set(participants.filter(p=>p.answers[q.id]===0).map(p=>p.settings[q.id][f.key]))].map(value=>({value,label:f.labels?.[value]??value,count:participants.filter(p=>p.answers[q.id]===0&&p.settings[q.id][f.key]===value).length}))})),
      options:q.options.map((label,index)=>({label,count:participants.filter(p=>p.answers[q.id]===index).length}))}))};
  }
  async function loadIssues(s,fetcher){
    if(!/^[a-z0-9_.-]+\/[a-z0-9_.-]+$/i.test(s.repository))throw Error('Некорректный репозиторий.');
    const all=[];
    for(let page=1;page<=100;page++){
      const response=await fetcher('https://api.github.com/repos/'+s.repository+'/issues?state=all&per_page=100&page='+page,
        {headers:{Accept:'application/vnd.github+json'},credentials:'omit',redirect:'error'});
      if(!response.ok)throw Error('GitHub API: HTTP '+response.status+'. Ответы не загружены; попробуй позже.');
      const data=await response.json();
      if(!Array.isArray(data))throw Error('Некорректный ответ GitHub.');
      all.push(...data);
      if(data.length<100&&!(response.headers?.get('link')||'').includes('rel="next"'))return all;
    }
    throw Error('Слишком много страниц; неполные результаты не показаны.');
  }
  return {validate,marker,prefix,issueBody,issueUrl,readIssue,aggregate,loadIssues};
});
