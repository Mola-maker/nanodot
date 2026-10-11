/* Preferences only: no credential inputs, inference, analytics or remote assets. */
(() => {
  const html = document.documentElement;
  const params = new URLSearchParams(location.search);
  const saved = name => { try { return localStorage.getItem(name); } catch { return null; } };
  const store = (name, value) => { try { localStorage.setItem(name, value); } catch { /* private mode */ } };
  let lang = params.get('lang') ?? saved('nanodot-site-lang') ?? (/^zh/i.test(navigator.language) ? 'zh' : 'en');
  let theme = params.get('theme') ?? saved('nanodot-site-theme');
  const languageButton = document.getElementById('lang');
  const themeButton = document.getElementById('theme');
  function applyLanguage() {
    lang = lang === 'zh' ? 'zh' : 'en';
    html.lang = lang === 'zh' ? 'zh-CN' : 'en';
    languageButton.textContent = lang === 'zh' ? 'English' : '中文';
    languageButton.setAttribute('aria-label', lang === 'zh' ? '切换到英文' : 'Switch to Chinese');
    document.querySelectorAll('[data-docs], [data-docs-path]').forEach(link => {
      link.href = `/docs/${lang === 'zh' ? 'zh/' : ''}${link.dataset.docsPath ?? ''}`;
    });
    const titles = { '/': ['Meet Dot', '认识 Dot'], '/connect/': ['Connect', '接入'], '/privacy.html': ['Privacy', '隐私'], '/privacy': ['Privacy', '隐私'] };
    document.title = `${(titles[location.pathname] ?? ['404', '404'])[lang === 'zh' ? 1 : 0]} · Nanodot`;
    applyTheme();
  }
  function applyTheme() {
    if (theme === 'light' || theme === 'dark') html.dataset.dotTheme = theme;
    else delete html.dataset.dotTheme;
    const dark = theme === 'dark' || (!theme && matchMedia('(prefers-color-scheme: dark)').matches);
    themeButton.textContent = lang === 'zh' ? (dark ? '浅色外观' : '深色外观') : (dark ? 'Light theme' : 'Dark theme');
    themeButton.setAttribute('aria-label', lang === 'zh' ? (dark ? '切换到浅色外观' : '切换到深色外观') : (dark ? 'Switch to light theme' : 'Switch to dark theme'));
  }
  if (!['light', 'dark'].includes(theme)) theme = null;
  languageButton.addEventListener('click', () => { lang = lang === 'zh' ? 'en' : 'zh'; store('nanodot-site-lang', lang); applyLanguage(); });
  themeButton.addEventListener('click', () => { const dark = theme === 'dark' || (!theme && matchMedia('(prefers-color-scheme: dark)').matches); theme = dark ? 'light' : 'dark'; store('nanodot-site-theme', theme); applyTheme(); });
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
  document.querySelectorAll('.download-card').forEach(card => {
    const time = card.querySelector('time');
    if (time && Date.parse(time.dateTime) <= Date.now()) {
      card.querySelector('.expired').hidden = false;
      card.querySelector('.artifact-link').href = '/docs/build';
      card.querySelector('.artifact-link').innerHTML = '<span class="en">Build from source</span><span class="zh">从源码构建</span>';
    }
  });
  applyLanguage();
})();
