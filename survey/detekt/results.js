'use strict';
const spec=globalThis.DETEKT_SURVEY,model=globalThis.DetektBallot;
function el(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;}
function render(result){
  const root=document.getElementById('results');root.replaceChildren();
  root.append(el('h2','Ответили: '+result.participants.length));
  for(const [i,q]of result.questions.entries()){
    const section=el('section');section.append(el('h3',(i+1)+'. '+q.title));
     const list=el('ul');for(const opt of q.options)list.append(el('li',opt.label+': '+opt.count));
     for(const setting of q.settings||[])for(const choice of setting.values){
       const item=el('li',setting.label+' — '+choice.label+': '+choice.count+' (среди включивших)');
       const technical=el('details');technical.append(el('summary','Техническое значение'),el('code',setting.key+' = '+choice.value));
       item.append(technical);list.append(item);
     }
    section.append(list);root.append(section);
  }
  root.append(el('h2','Кто что выбрал и комментарии'));
  for(const p of result.participants){
    const details=el('details');details.append(el('summary',p.name+' · @'+p.login));
    const link=el('a','Посмотреть ответ в GitHub');link.href=p.issueUrl;link.target='_blank';link.rel='noopener noreferrer';details.append(link);
    for(const [i,q]of spec.questions.entries()){
      details.append(el('p',(i+1)+'. '+q.title+'\n'+q.options[p.answers[q.id]]));
      for(const [key,value]of Object.entries(p.settings?.[q.id]||{})){
        const field=q.settings.find(f=>f.key===key);
        details.append(el('p',field.label+' — '+(field.labels?.[value]??value)));
        const technical=el('details');technical.append(el('summary','Техническое значение'),el('code',key+': '+value));details.append(technical);
      }
      if(p.comments[q.id])details.append(el('p','Комментарий: '+p.comments[q.id]));
    }
    if(p.comment)details.append(el('p','Общий комментарий: '+p.comment));
    root.append(details);
  }
  if(result.rejected.length){
    const details=el('details');details.append(el('summary','Не учтены ответы: '+result.rejected.length));
    for(const r of result.rejected)details.append(el('p','#'+r.number+': '+r.reason));root.append(details);
  }
}
async function load(){
  const button=document.getElementById('load'),status=document.getElementById('status');button.disabled=true;
  status.textContent='Читаем публичные Issues…';
  try{render(model.aggregate(spec,await model.loadIssues(spec,fetch)));status.textContent='Обновлено: '+new Date().toLocaleString('ru-RU');}
  catch(e){status.textContent=e.message;}
  finally{button.disabled=false;}
}
document.getElementById('load').addEventListener('click',load);
// Explicit user action only; opening this local draft never starts a GitHub request.
