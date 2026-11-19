'use strict';
const spec = globalThis.FORMATTING_SURVEY;
const model = globalThis.StyleBallot;
const container = document.getElementById('questions');
function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
for (const [index,q] of spec.questions.entries()) {
  const card = element('article', undefined, 'question'); card.id = q.id;
  const fieldset = element('fieldset'); const legend = element('legend', (index+1)+'. '+q.title); fieldset.append(legend);
  fieldset.append(element('p', q.explanation));
  if (q.note) fieldset.append(element('p', q.note, 'tip'));
  const longest = Math.max(...Object.values(q.examples).flatMap(code=>code.split('\n').map(line=>line.length)));
  const grid = element('div', undefined, longest > 85 ? 'options long' : 'options');
  for (const choice of ['A','B']) {
    const label = element('label', undefined, 'option'); const input = element('input');
    input.type = 'radio'; input.name = q.id; input.value = choice; input.setAttribute('aria-label', (choice==='A'?'А':'Б')+': '+q.labels[choice]);
    label.append(input, element('strong', choice==='A'?'А':'Б'), element('span', ' — '+q.labels[choice]));
    const pre = element('pre'); pre.append(element('code',q.examples[choice])); label.append(pre); grid.append(label);
  }
  fieldset.append(grid);
  const details = element('details'); details.append(element('summary','Можно ли это автоматически форматировать?'));
  details.append(element('p',q.capability.status==='strict'?'Оба варианта проверены: форматтер приводит к выбранному виду из другого оформления и не меняет результат повторно.':q.capability.status==='retains_only'?'Вариант Б проверен. Вариант А пока сохраняется, но автоматически выстроить его из другого оформления не удалось. Понадобится доработка своего правила.':'Вариант Б проверен. Для точного варианта А текущего прототипа пока недостаточно; ограничения и ближайший результат показаны в техническом отчёте.'));
  const link=element('a','Открыть проверку этого вопроса');link.href='technical.html#'+q.id;details.append(link);fieldset.append(details);card.append(fieldset);container.append(card);
}
function collect() {
  const answers = {};
  for (const q of spec.questions) answers[q.id] = document.querySelector('input[name="'+q.id+'"]:checked')?.value || null;
  return {schema:1,survey:spec.id,version:spec.version,name:document.getElementById('name').value,comment:document.getElementById('comment').value,answers};
}
function progress() {const data=collect();document.getElementById('progress').textContent='Выбрано '+Object.values(data.answers).filter(Boolean).length+' из '+spec.questions.length+'. А — образец, Б — предложение.';}
function message(text){document.getElementById('message').textContent=text;}
function download(name,text,type){const url=URL.createObjectURL(new Blob([text],{type}));const a=element('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
document.addEventListener('change',progress);
document.getElementById('download').addEventListener('click',()=>{try{const vote=model.validate(spec,collect());download('formatting-answers.json',JSON.stringify(vote,null,2),'application/json');message('Ответы сохранены в файл.');}catch(e){message(e.message);}});
document.getElementById('github').addEventListener('click',()=>{try{const vote=model.validate(spec,collect(),true);const url=model.issueUrl(spec,vote);if(url){window.open(url,'_blank','noopener,noreferrer');message('Открыт черновик на GitHub. Голос будет учтён после отправки Issue.');}else{download('formatting-issue.txt',model.issueBody(spec,vote),'text/plain;charset=utf-8');message('Ответ слишком длинный для ссылки. Скопируй содержимое скачанного formatting-issue.txt в новое Issue GitHub.');}}catch(e){message(e.message);}});
document.getElementById('import-button').addEventListener('click',()=>document.getElementById('import').click());
document.getElementById('import').addEventListener('change',async event=>{try{const file=event.target.files[0];if(!file)return;if(file.size>100000)throw new Error('Файл слишком большой.');const vote=model.validate(spec,JSON.parse(await file.text()));document.getElementById('name').value=vote.name;document.getElementById('comment').value=vote.comment;for(const q of spec.questions)for(const input of document.querySelectorAll('input[name="'+q.id+'"]'))input.checked=input.value===vote.answers[q.id];progress();message('Ответы загружены.');}catch(e){message(e.message);}event.target.value='';});
progress();
