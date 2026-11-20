'use strict';
const spec=globalThis.FORMATTING_SURVEY;
const model=globalThis.StyleBallot;
function node(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;}
function changedLines(a,b){const x=a.split('\n'),y=b.split('\n');const changedA=new Set(),changedB=new Set();let i=0,j=0;while(i<x.length&&j<y.length){if(x[i]===y[j]){i++;j++;continue;}let ai=x.length,bj=y.length,best=Infinity;for(let m=i;m<x.length;m++)for(let n=j;n<y.length;n++)if(x[m]===y[n]&&m-i+n-j<best){best=m-i+n-j;ai=m;bj=n;}for(let m=i;m<ai;m++)changedA.add(m);for(let n=j;n<bj;n++)changedB.add(n);i=ai;j=bj;}for(;i<x.length;i++)changedA.add(i);for(;j<y.length;j++)changedB.add(j);return[changedA,changedB];}
function code(text,changed,id){const pre=node('pre'),code=node('code');code.id=id;const lines=text.split('\n');lines.forEach((line,i)=>{const span=node('span',line);span.className=changed.has(i)?'changed':'line';code.append(span);if(i<lines.length-1)code.append(document.createTextNode('\n'));});pre.append(code);return pre;}
for(const [index,q]of spec.questions.entries()){
 const section=node('section');section.className='panel question';section.id=q.id;section.append(node('h2',(index+1)+'. '+q.title));const badge=node('p',q.group);badge.className='badge';section.append(badge);
 const fieldset=node('fieldset');fieldset.append(node('legend','Какой вариант удобнее читать?'));
 for(const choice of ['A','B']){const label=node('label'),input=node('input');input.type='radio';input.name=q.id;input.value=choice;label.append(input,document.createTextNode(choice==='A'?' А — исходный фрагмент ':' Б — альтернативный вариант '));fieldset.append(label);}section.append(fieldset);
 for(const ex of q.source_examples){const source=node('p',ex.file+':'+ex.line+(ex.end_line!==ex.line?'–'+ex.end_line:''));source.className='source';section.append(source);if(ex.partial_header)section.append(node('p','Показан настоящий заголовок объявления; тело не показано.'));
  const flags=changedLines(ex.A,ex.B),grid=node('div');grid.className='comparison'+(Math.max(...(ex.A+'\n'+ex.B).split('\n').map(l=>l.length))>82?' long':'');for(const [ci,choice]of ['A','B'].entries()){const col=node('div');col.append(node('h3',choice==='A'?'А — как в исходнике':'Б — альтернативный вариант'),code(ex[choice],flags[ci],ex.id+'-'+choice));grid.append(col);}section.append(grid);
  const details=node('details');details.append(node('summary','Источник и контекст'));details.append(node('p','Снимок от '+spec.source_date+'; строки '+ex.line+'–'+ex.end_line),node('p','SHA-256 файла: '+ex.source_sha256));if(ex.original_context){details.append(node('p','Окружение в оригинале:'),code(ex.original_context,new Set(),ex.id+'-context'));}section.append(details);
 }
 const details=node('details');details.append(node('summary','Как работает правило и на что ещё влияет'),node('p',q.scope.mechanism),node('p',q.scope.other_structures));section.append(details);section.append(node('p','Проверено на этих фрагментах: исходный А восстановлен из Б, повтор стабилен, check проходит.'));document.getElementById('questions').append(section);
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
document.getElementById('github').addEventListener('click',()=>{try{const vote=model.validate(spec,collect(),true);const url=model.issueUrl(spec,vote);if(url){window.open(url,'_blank','noopener,noreferrer');message('Открыт черновик на GitHub. Голос будет учтён после отправки Issue. Затем нажми в нём «Посмотреть результаты».');}else{message('Ответ слишком длинный для ссылки GitHub. Сократи комментарий и попробуй ещё раз; выбранные варианты сохранятся на странице.');}}catch(e){message(e.message);}});
progress();
