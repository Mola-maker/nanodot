// nanomuse.cn — the bar's switches, the star count and the demo frame. The inline script in
// each page's <head> has already set data-lang and data-theme on <html> before first paint;
// this file runs after the page is there. No framework, nothing fetched from outside except
// GitHub's API for the star count and the demo frame itself.
(function () {
  "use strict";

  var root = document.documentElement;
  var DEMO = "https://demo.nanomuse.dev";
  // a local preview of this page may point the frame at a showcase served on this machine
  // (python3 -m http.server from demo/showcase/site/page/, or the gateway): ?demo=http://localhost:PORT,
  // honoured only while this page itself is on localhost
  if (/^(localhost|127\.0\.0\.1)$/.test(location.hostname)) {
    var want = new URLSearchParams(location.search).get("demo");
    if (want && /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(want)) DEMO = want;
  }
  var frame = document.getElementById("demo-frame");
  var title = document.querySelector("title");

  function lang() {
    return root.getAttribute("data-lang") === "zh" ? "zh" : "en";
  }
  function dark() {
    // light unless this visitor chose dark; the inline script has set the attribute already
    return root.getAttribute("data-theme") === "dark";
  }
  function tell(msg) {
    // the showcase in the frame follows the site's language and theme (demo/showcase/README.md)
    if (frame && frame.src && frame.contentWindow) {
      try {
        frame.contentWindow.postMessage(msg, DEMO);
      } catch (e) {
        /* the frame is not there yet */
      }
    }
  }

  // ---- language -------------------------------------------------------------------------
  // The docs site has an English tree at /docs/ and a Chinese one at /docs/zh/ with the same
  // page names; every link into it on this site follows the chosen language. GitHub links
  // to the Markdown sources are left alone.
  var docLinks = Array.prototype.filter.call(document.querySelectorAll('a[href*="docs/"]'), function (a) {
    var h = a.getAttribute("href") || "";
    return !/^https?:/i.test(h) || h.indexOf("nanomuse.cn/docs/") >= 0;
  });
  function applyDocs(l) {
    docLinks.forEach(function (a) {
      var h = a.getAttribute("href");
      a.setAttribute("href", h.replace(/docs\/(zh\/)?/, l === "zh" ? "docs/zh/" : "docs/"));
    });
  }
  function applyLang(l) {
    root.setAttribute("data-lang", l);
    root.lang = l === "zh" ? "zh-CN" : "en";
    if (title) {
      var t = title.getAttribute(l === "zh" ? "data-zh" : "data-en");
      if (t) document.title = t;
    }
    applyDocs(l);
    tell({ type: "nanomuse:lang", lang: l });
  }
  applyLang(lang());
  var langBtn = document.getElementById("lang");
  if (langBtn) {
    langBtn.addEventListener("click", function () {
      var l = lang() === "zh" ? "en" : "zh";
      try {
        localStorage.setItem("nm-lang", l);
      } catch (e) {
        /* private mode */
      }
      applyLang(l);
    });
  }

  // ---- theme ----------------------------------------------------------------------------
  var themeBtn = document.getElementById("theme");
  var themeMeta = document.querySelector('meta[name="theme-color"]');
  if (themeMeta && dark()) themeMeta.setAttribute("content", "#121212");
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var t = dark() ? "light" : "dark";
      root.setAttribute("data-theme", t);
      if (themeMeta) themeMeta.setAttribute("content", t === "dark" ? "#121212" : "#ffffff");
      try {
        localStorage.setItem("nm-theme", t);
      } catch (e) {
        /* private mode */
      }
      tell({ type: "nanomuse:theme", theme: t });
    });
  }

  // ---- the star count -------------------------------------------------------------------
  // GitHub's API, once an hour; without a number the link still says GitHub.
  var starsEl = document.querySelector("#stars .n");
  function fmt(n) {
    if (n < 1000) return String(n);
    var k = (n / 1000).toFixed(n < 10000 ? 1 : 0);
    return k.replace(/\.0$/, "") + "k";
  }
  function showStars(n) {
    if (starsEl && typeof n === "number" && n >= 0) starsEl.textContent = fmt(n);
  }
  if (starsEl) {
    var cached = null;
    try {
      cached = JSON.parse(localStorage.getItem("nm-stars") || "null");
    } catch (e) {
      cached = null;
    }
    var fresh = cached && typeof cached.n === "number" && Date.now() - (cached.t || 0) < 3600000;
    if (cached && typeof cached.n === "number") showStars(cached.n);
    if (!fresh && window.fetch) {
      fetch("https://api.github.com/repos/nano-muse/nanoMuse", { headers: { Accept: "application/vnd.github+json" } })
        .then(function (r) {
          return r.ok ? r.json() : null;
        })
        .then(function (j) {
          if (!j || typeof j.stargazers_count !== "number") return;
          showStars(j.stargazers_count);
          try {
            localStorage.setItem("nm-stars", JSON.stringify({ n: j.stargazers_count, t: Date.now() }));
          } catch (e) {
            /* private mode */
          }
        })
        .catch(function () {
          /* no number, still GitHub */
        });
    }
  }

  // ---- the demo frame -------------------------------------------------------------------
  // Loaded only where it can be used (from 1000 px, the width at which the showcase keeps the
  // phone and its panel side by side and fits them into the height it is given); narrower
  // screens see the still and a link. The phone in the frame turns itself on; the showcase
  // starts a Muse once it has reason to think a person is looking (?embed=1,
  // demo/showcase/site/page/page.js in the main repository).
  if (frame) {
    var wide = window.matchMedia("(min-width: 1000px)");
    var load = function () {
      if (frame.src || !wide.matches) return;
      frame.src = DEMO + "/?embed=1&lang=" + lang() + (dark() ? "&theme=dark" : "");
    };
    load();
    if (wide.addEventListener) wide.addEventListener("change", load);
    else if (wide.addListener) wide.addListener(load);
    // Nothing in the showcase page scrolls (it lays itself out in the frame's height), so a
    // wheel turned over the frame moves this page: the browser hands the scroll on to the
    // parent when the frame has nowhere to go. Over the phone's screen the phone keeps it.
  }

  // ---- "More below" ---------------------------------------------------------------------
  // The line under the frame goes once the visitor has scrolled; the page's own smooth
  // scrolling takes the click to the next section.
  var onScroll = function () {
    if (window.scrollY > 40) {
      root.classList.add("scrolled");
      window.removeEventListener("scroll", onScroll);
    }
  };
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });
})();
