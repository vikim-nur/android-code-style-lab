'use strict';
const spec=globalThis.DETEKT_SURVEY,model=globalThis.DetektBallot;
const byId=id=>document.getElementById(id);
function collect(){
  const answers={},comments={},settings={};
  for(const q of spec.questions){
    const input=document.querySelector('input[name="'+q.id+'"]:checked');
    answers[q.id]=input?Number(input.value):null;
    comments[q.id]=document.querySelector('[data-comment="'+q.id+'"]').value;
    if(answers[q.id]===0){
      settings[q.id]={};
      for(const input of document.querySelectorAll('[data-setting="'+q.id+'"]'))settings[q.id][input.dataset.key]=input.value;
    }
  }
  return {schema:1,survey:spec.id,version:spec.version,name:byId('name').value,answers,settings,comments,comment:byId('comment').value};
}
function progress(){
  const draft=collect(),n=Object.values(draft.answers).filter(v=>v!==null).length;
  for(const q of spec.questions){
    const enabled=draft.answers[q.id]===0,block=document.querySelector('[data-settings="'+q.id+'"]');
    block.hidden=!enabled;
    for(const input of block.querySelectorAll('[data-setting]')){input.disabled=!enabled;input.required=enabled;}
  }
  byId('progress').textContent='Ответов: '+n+' из '+spec.questions.length;
}
document.addEventListener('change',progress);
byId('github').addEventListener('click',()=>{
  byId('manual').hidden=true;
  try{
    const draft=collect(),missing=spec.questions.find(q=>draft.answers[q.id]===null);
    if(missing){
      byId('message').textContent='Ответь на каждый вопрос: включить или выключить. Пропуск не считается ответом.';
      document.querySelector('input[name="'+missing.id+'"]').focus();return;
    }
    const vote=model.validate(spec,draft),url=model.issueUrl(spec,vote);
    if(url){
      window.open(url,'_blank','noopener,noreferrer');
      byId('message').textContent='Ответ ещё не отправлен. На GitHub нажми Submit new issue; затем перейди к результатам.';
    }else{
      byId('manual-title').value=model.prefix(spec)+' '+vote.name;
      byId('manual-body').value=model.issueBody(spec,vote);
      byId('manual').hidden=false;
      byId('message').textContent='Комментарии не помещаются в ссылку. Они не потерялись: скопируй заголовок и текст ниже в новый Issue.';
    }
  }catch(e){byId('message').textContent=e.message;}
});
progress();
