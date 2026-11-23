(function(root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.StyleBallot = api;
})(typeof globalThis === 'object' ? globalThis : this, function() {
  'use strict';
  function validate(spec, input, requireChoice = false) {
    if (!input || input.schema !== 1 || input.survey !== spec.id || input.version !== spec.version) {
      throw new Error('Ответ относится к другой версии анкеты.');
    }
    if (typeof input.name !== 'string' || !input.name.trim() || input.name.trim().length > 80 || /[\u0000-\u001f]/.test(input.name)) {
      throw new Error('Укажите имя: от 1 до 80 символов, одной строкой.');
    }
    if (typeof input.comment !== 'string' || input.comment.length > 1000) throw new Error('Комментарий должен быть не длиннее 1000 символов.');
    if (!input.answers || typeof input.answers !== 'object' || Array.isArray(input.answers)) throw new Error('Некорректные ответы.');
    const ids = new Set(spec.questions.map(q => q.id));
    if (Object.keys(input.answers).some(id => !ids.has(id))) throw new Error('В ответе есть неизвестные вопросы.');
    const answers = {};
    for (const id of ids) {
      const value = input.answers[id] === undefined ? null : input.answers[id];
      if (value !== null && value !== 'A' && value !== 'B') throw new Error('Некорректный выбор: ' + id);
      answers[id] = value;
    }
    if (requireChoice && !Object.values(answers).some(Boolean)) throw new Error('Выберите хотя бы один вариант.');
    return {schema: 1, survey: spec.id, version: spec.version, name: input.name.trim(), comment: input.comment, answers};
  }
  const marker = spec => '<!-- style-ballot:' + spec.id + ' -->';
  const prefix = spec => '[style-ballot:' + spec.id + ']';
  function issueBody(spec, input) {
    const vote = validate(spec, input, true);
    const selections = spec.questions.filter(q => vote.answers[q.id]).map(q => q.id + ': ' + (vote.answers[q.id] === 'A' ? 'А' : 'Б')).join(' · ');
    return ['Анкета форматирования: А — образец «Мой О!», Б — альтернатива.',
      'Имя: ' + vote.name, '', '[Посмотреть результаты голосования](' + spec.pageUrl + 'results.html?autoload=1)', '', selections, '', 'Вопросы: ' + spec.pageUrl, '',
      marker(spec), '```json', JSON.stringify(vote, null, 2), '```'].join('\n');
  }
  function issueUrl(spec, input) {
    const vote = validate(spec, input, true);
    if (Object.values(vote.answers).some(value => value === null)) throw new Error('Выберите А или Б в каждом вопросе.');
    const base = 'https://github.com/' + spec.repository + '/issues/new';
    const url = base + '?title=' + encodeURIComponent(prefix(spec) + ' ' + vote.name) + '&body=' + encodeURIComponent(issueBody(spec, vote));
    return url.length <= 7500 ? url : null;
  }
  function readIssue(spec, issue) {
    const body = typeof issue.body === 'string' ? issue.body : '';
    if (body.split(/\r?\n/).filter(line => line === marker(spec)).length !== 1) throw new Error('Не найден блок ответа этой анкеты.');
    const tail = body.slice(body.indexOf(marker(spec)) + marker(spec).length);
    const blocks = [...tail.matchAll(/```json\r?\n([\s\S]*?)\r?\n```/g)];
    if (blocks.length !== 1) throw new Error('В ответе должен быть один JSON-блок.');
    return validate(spec, JSON.parse(blocks[0][1]), true);
  }
  function aggregate(spec, issues) {
    if (!Array.isArray(issues)) throw new Error('GitHub вернул неожиданный ответ.');
    const latest = new Map();
    let eligible = 0;
    const rejected = [];
    for (const issue of issues) {
      if (!issue || issue.pull_request || issue.user?.type === 'Bot') continue;
      const body = typeof issue.body === 'string' ? issue.body : '';
      if (!(typeof issue.title === 'string' && issue.title.startsWith(prefix(spec))) && !body.includes(marker(spec))) continue;
      const login = issue.user?.login;
      if (typeof login !== 'string' || !/^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}$/i.test(login) || !Number.isSafeInteger(issue.number) || issue.number < 1) {
        rejected.push({number: issue.number, reason: 'Некорректный автор или номер Issue.'}); continue;
      }
      eligible++;
      const identity = Number.isSafeInteger(issue.user.id) ? 'id:' + issue.user.id : 'login:' + login.toLowerCase();
      const old = latest.get(identity);
      if (!old || issue.number > old.number) latest.set(identity, issue);
    }
    const participants = [];
    for (const issue of latest.values()) {
      try {
        const vote = readIssue(spec, issue);
        participants.push({...vote, login: issue.user.login, number: issue.number,
          issueUrl: 'https://github.com/' + spec.repository + '/issues/' + issue.number});
      } catch (error) { rejected.push({number: issue.number, reason: error.message}); }
    }
    participants.sort((a,b) => a.name.localeCompare(b.name, 'ru'));
    const questions = spec.questions.map(q => {
      const a = participants.filter(p => p.answers[q.id] === 'A');
      const b = participants.filter(p => p.answers[q.id] === 'B');
      return {id: q.id, title: q.title, A: a.length, B: b.length, total: a.length + b.length,
        winner: a.length === b.length ? (a.length === 0 ? 'none' : 'tie') : a.length > b.length ? 'A' : 'B',
        votersA: a.map(p => p.login), votersB: b.map(p => p.login)};
    });
    return {participants, questions, rejected, superseded: Math.max(0, eligible - latest.size)};
  }
  async function loadIssues(spec, fetcher) {
    if (!/^[a-z0-9_.-]+\/[a-z0-9_.-]+$/i.test(spec.repository)) throw new Error('Некорректный адрес репозитория.');
    const issues = [];
    for (let page = 1; page <= 100; page++) {
      const url = 'https://api.github.com/repos/' + spec.repository + '/issues?state=all&per_page=100&page=' + page;
      const response = await fetcher(url, {headers: {Accept: 'application/vnd.github+json'}, credentials: 'omit', redirect: 'error'});
      if (!response.ok) throw new Error('GitHub API: HTTP ' + response.status + '. Результаты не загружены; попробуйте позже.');
      const data = await response.json();
      if (!Array.isArray(data)) throw new Error('GitHub вернул неожиданный ответ.');
      issues.push(...data);
      const link = response.headers?.get('link') || '';
      if (!link.includes('rel="next"') && data.length < 100) return issues;
      if (!link.includes('rel="next"') && data.length === 100) continue;
    }
    throw new Error('Слишком много страниц: неполные результаты не показаны.');
  }
  function markdownResults(spec, result, options = {}) {
    const escape = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\|/g, '\\|').replace(/([\[\]*_`])/g, '\\$1');
    const lines = ['# ' + (options.demo ? 'ДЕМОНСТРАЦИЯ — вымышленные ответы' : 'Предварительные итоги выбора форматирования'), '',
      'Анкета: ' + spec.id + ' · версия ' + spec.version, '',
      'А — образец «Мой О!», Б — альтернатива. Ответили: ' + result.participants.length + '.', '',
      '| Вопрос | А | Б | Итог |', '|---|---:|---:|---|'];
    for (const [index,q] of result.questions.entries()) lines.push('| ' + (index+1) + '. ' + escape(q.title) + ' | ' + q.A + ' | ' + q.B + ' | ' + (q.winner === 'A' ? 'А' : q.winner === 'B' ? 'Б' : q.winner === 'tie' ? 'Поровну' : 'Нет голосов') + ' |');
    lines.push('', '## Ответы участников', '');
    for (const p of result.participants) {
      lines.push('- ' + escape(p.name) + ' (@' + p.login + '): ' + spec.questions.map((q,index) => (index+1) + ': ' + (p.answers[q.id] === 'A' ? 'А' : p.answers[q.id] === 'B' ? 'Б' : '—')).join('; ') + '.');
    }
    lines.push('', 'Пропуски не считаются голосами. Итоговое сочетание правил требует проверки и утверждения лидами.', '');
    return lines.join('\n');
  }
  return {validate, marker, prefix, issueBody, issueUrl, readIssue, aggregate, loadIssues, markdownResults};
});
