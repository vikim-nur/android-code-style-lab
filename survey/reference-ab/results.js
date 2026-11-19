'use strict';
const spec=globalThis.FORMATTING_SURVEY, model=globalThis.StyleBallot;
const demo=new URLSearchParams(location.search).get('demo')==='1';
function el(tag,text){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;}
function render(result){
  const root=document.getElementById('results');root.replaceChildren();
  const summary=el('section');summary.className='panel';summary.append(el('h2','Ответили: '+result.participants.length));
  if(!result.participants.length)summary.append(el('p','Корректных ответов этой версии анкеты пока нет.'));
  const table=el('table'),head=el('tr');for(const text of ['Вопрос','А','Б','Сейчас лидирует'])head.append(el('th',text));table.append(head);
  for(const [index,q] of result.questions.entries()){const row=el('tr');row.id=q.id;row.append(el('td',(index+1)+'. '+q.title),el('td',String(q.A)),el('td',String(q.B)),el('td',q.winner==='none'?'Нет голосов':q.winner==='tie'?'Поровну':q.winner==='A'?'А — образец':'Б — альтернатива'));table.append(row);}
  const scroll=el('div');scroll.className='scroll';scroll.append(table);summary.append(scroll);root.append(summary);
  const people=el('section');people.className='panel';people.append(el('h2','Кто что выбрал'));
  for(const p of result.participants){const details=el('details');details.append(el('summary',p.name+' · @'+p.login));
    if(!demo){const link=el('a','Открыть ответ в GitHub');link.href=p.issueUrl;link.target='_blank';link.rel='noopener noreferrer';details.append(link);}
    const list=el('ul');for(const [index,q]of spec.questions.entries()){const value=p.answers[q.id];list.append(el('li',(index+1)+'. '+q.title+' — '+(value===null?'без ответа':value==='A'?'А':'Б')));}details.append(list);if(p.comment)details.append(el('p',p.comment));people.append(details);}
  root.append(people);
  if(result.rejected.length){const panel=el('details');panel.append(el('summary','Не учтены ответы: '+result.rejected.length));for(const x of result.rejected)panel.append(el('p','#'+x.number+': '+x.reason));root.append(panel);}
}
async function load(){const button=document.getElementById('refresh');button.disabled=true;document.getElementById('status').textContent='Читаем ответы…';try{const issues=await model.loadIssues(spec,fetch);render(model.aggregate(spec,issues));document.getElementById('status').textContent='Загружено '+new Date().toLocaleString('ru-RU')+'.'}catch(e){document.getElementById('status').textContent=e.message;document.getElementById('status').className='message status-error';}finally{button.disabled=false;}}
document.getElementById('refresh').addEventListener('click',load);
if(demo){const note=el('p','ДЕМОНСТРАЦИЯ: вымышленные участники и ответы. Это не голоса команды.');note.className='demo';document.getElementById('demo').append(note);document.getElementById('refresh').hidden=true;
 const issues=[1,2,3].map(n=>{const answers={};for(const [index,q]of spec.questions.entries())answers[q.id]=n===1?'A':n===2?'B':index%3===0?'A':index%3===1?'B':null;const vote={schema:1,survey:spec.id,version:spec.version,name:'Пример участника '+n,comment:'',answers};return{number:n,title:model.prefix(spec)+' пример',body:model.issueBody(spec,vote),user:{id:n,login:'example-reader-'+n,type:'User'}};});render(model.aggregate(spec,issues));document.getElementById('status').textContent='Показан пример подсчёта, не реальные результаты.';
}


if (!demo && new URLSearchParams(location.search).get('autoload') === '1') load();
