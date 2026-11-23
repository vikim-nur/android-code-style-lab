'use strict';
const spec=globalThis.FORMATTING_SURVEY;
const model=globalThis.StyleBallot;
function node(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;}
function changedLines(a,b){const x=a.split('\n'),y=b.split('\n');const changedA=new Set(),changedB=new Set();let i=0,j=0;while(i<x.length&&j<y.length){if(x[i]===y[j]){i++;j++;continue;}let ai=x.length,bj=y.length,best=Infinity;for(let m=i;m<x.length;m++)for(let n=j;n<y.length;n++)if(x[m]===y[n]&&m-i+n-j<best){best=m-i+n-j;ai=m;bj=n;}for(let m=i;m<ai;m++)changedA.add(m);for(let n=j;n<bj;n++)changedB.add(n);i=ai;j=bj;}for(;i<x.length;i++)changedA.add(i);for(;j<y.length;j++)changedB.add(j);return[changedA,changedB];}
function highlightedLines(a,b){
 const diff=changedLines(a,b);
 const fullyChanged=(text,changed)=>text.split('\n').every((line,index)=>!line.trim()||changed.has(index));
 // When the entire comparison differs, a solid yellow block adds no information.
 return fullyChanged(a,diff[0])&&fullyChanged(b,diff[1])?[new Set(),new Set()]:diff;
}
function code(text,changed,id){const pre=node('pre'),code=node('code');code.id=id;const lines=text.split('\n');lines.forEach((line,i)=>{const span=node('span',line);span.className=changed.has(i)?'changed':'line';code.append(span);if(i<lines.length-1)code.append(document.createTextNode('\n'));});pre.append(code);return pre;}
for(const [index,q]of spec.questions.entries()){
 const section=node('section');section.className='panel question';section.id=q.id;section.append(node('h2',(index+1)+'. '+q.title));const badge=node('p',q.group);badge.className='badge';section.append(badge);
 const fieldset=node('fieldset');fieldset.append(node('legend','Какой вариант удобнее читать?'));
 const grid=node('div');grid.className='comparison'+(Math.max(...q.source_examples.flatMap(ex=>(ex.A+'\n'+ex.B).split('\n').map(line=>line.length)))>82?' long':'');
 const highlights=q.source_examples.map(ex=>highlightedLines(ex.A,ex.B));
 for(const [ci,choice]of ['A','B'].entries()){
  const label=node('label');label.className='option';const input=node('input');input.type='radio';input.name=q.id;input.value=choice;
  const title=choice==='A'?'А — как в исходнике':'Б — альтернативный вариант';input.setAttribute('aria-label',title);label.append(input,node('strong',title));
  for(const [exampleIndex,ex] of q.source_examples.entries()){
   if(q.source_examples.length>1)label.append(node('p','Пример '+(exampleIndex+1)));
   if(ex.partial_header)label.append(node('p','Заголовок объявления; тело не показано.'));
   label.append(code(ex[choice],highlights[exampleIndex][ci],ex.id+'-'+choice));
  }
  grid.append(label);
 }
 fieldset.append(grid);section.append(fieldset);
 const help=globalThis.RULE_HELP[q.id];const details=node('details');details.append(node('summary','Что меняется и где ещё сработает'),node('p',help.plain),node('p',help.affects));
 const links=node('p');for(const [title,href]of [['Правило и настройки','how-it-works.html#'+q.id],['Код правила',help.source]]){const link=node('a',title);link.href=href;links.append(link,document.createTextNode(' '));}details.append(links);section.append(details);document.getElementById('questions').append(section);
 const jump=node('a',String(index+1));jump.href='#'+q.id;document.getElementById('jump').append(jump);
}
function collect() {
  const answers = {};
  for (const q of spec.questions) answers[q.id] = document.querySelector('input[name="'+q.id+'"]:checked')?.value || null;
  return {schema:1,survey:spec.id,version:spec.version,name:document.getElementById('name').value,comment:document.getElementById('comment').value,answers};
}
function progress() {const data=collect();document.getElementById('progress').textContent='Выбрано '+Object.values(data.answers).filter(Boolean).length+' из '+spec.questions.length+'. А — образец, Б — альтернатива.';}
function message(text){document.getElementById('message').textContent=text;}
document.addEventListener('change',progress);
document.getElementById('github').addEventListener('click',()=>{
 const vote=collect();
 const missing=spec.questions.filter(q=>vote.answers[q.id]===null);
 if(missing.length){
  message('Выбери А или Б в каждом вопросе. Осталось без ответа: '+missing.length+' из '+spec.questions.length+'.');
  document.querySelector('input[name="'+missing[0].id+'"]').focus();
  return;
 }
 try{
  const url=model.issueUrl(spec,vote);
  if(url){
   window.open(url,'_blank','noopener,noreferrer');
   message('Открыт черновик на GitHub. Голос будет учтён после отправки Issue. Затем нажми в нём «Посмотреть результаты».');
  }else{
   message('Ответ слишком длинный для ссылки GitHub. Сократи комментарий и попробуй ещё раз; выбранные варианты сохранятся на странице.');
  }
 }catch(e){message(e.message);}
});
progress();
