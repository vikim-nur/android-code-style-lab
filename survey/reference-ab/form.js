'use strict';
const spec=globalThis.FORMATTING_SURVEY;
const model=globalThis.StyleBallot;
function node(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;}
function displayCode(text,id){
 // F12 starts at the expression, so subtract the enclosing argument's indent from continuations.
 const baseIndent={'F12-1-B':16,'F12-2-B':8}[id];
 return baseIndent?text.replace(new RegExp('^ {'+baseIndent+'}','gm'),''):text;
}
function code(text,id){const pre=node('pre'),code=node('code',text);code.id=id;pre.append(code);return pre;}
for(const [index,q]of spec.questions.entries()){
 const section=node('section');section.className='panel question';section.id=q.id;section.append(node('h2',(index+1)+'. '+q.title));const badge=node('p',q.group);badge.className='badge';section.append(badge);
 const fieldset=node('fieldset');fieldset.append(node('legend','Какой вариант удобнее читать?'));
 const grid=node('div');grid.className='comparison'+(Math.max(...q.source_examples.flatMap(ex=>(ex.A+'\n'+ex.B).split('\n').map(line=>line.length)))>82?' long':'');
 for(const choice of ['A','B']){
  const label=node('label');label.className='option';const input=node('input');input.type='radio';input.name=q.id;input.value=choice;
  const title=choice==='A'?'А — как в исходнике':'Б — альтернативный вариант';input.setAttribute('aria-label',title);label.append(input,node('strong',title));
  for(const [exampleIndex,ex] of q.source_examples.entries()){
   if(q.source_examples.length>1)label.append(node('p','Пример '+(exampleIndex+1)));
   if(ex.partial_header)label.append(node('p','Заголовок объявления; тело не показано.'));
    const id=ex.id+'-'+choice;
    label.append(code(displayCode(ex[choice],id),id));
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
