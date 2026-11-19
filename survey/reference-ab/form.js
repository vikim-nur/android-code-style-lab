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
function progress() {const data=collect();document.getElementById('progress').textContent='Выбрано '+Object.values(data.answers).filter(Boolean).length+' из '+spec.questions.length+'. А — образец, Б — альтернатива.';}
function message(text){document.getElementById('message').textContent=text;}
document.addEventListener('change',progress);
document.getElementById('github').addEventListener('click',()=>{try{const vote=model.validate(spec,collect(),true);const url=model.issueUrl(spec,vote);if(url){window.open(url,'_blank','noopener,noreferrer');message('Открыт черновик на GitHub. Голос будет учтён после отправки Issue. Затем нажми в нём «Посмотреть результаты».');}else{message('Ответ слишком длинный для ссылки GitHub. Сократи комментарий и попробуй ещё раз; выбранные варианты сохранятся на странице.');}}catch(e){message(e.message);}});
progress();
