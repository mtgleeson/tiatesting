/*
 * Renders Mermaid diagrams in the reveal-md output deck.
 *
 * Two reasons this shim exists, both load-bearing:
 *
 * 1. reveal-md's bundled Mermaid plugin uses the selector
 *    `.stack.present > .present pre code.mermaid`, which only matches
 *    vertical-stack slides. Our deck is horizontal-only, so that selector
 *    never matches and the bundled plugin never renders anything.
 *
 * 2. Mermaid 10.x (reveal-md currently ships 10.9.1) uses `mermaid.run({nodes})`
 *    and expects `<div class="mermaid">` elements rather than the
 *    `<pre><code class="language-mermaid">` blocks that marked.js produces from
 *    fenced ```mermaid Markdown. We replace each code block with a div and
 *    then invoke the new API.
 *
 * The shim is loaded via `reveal-md --scripts <this-file>` (a separate file is
 * required because reveal.js's markdown plugin inserts content with innerHTML
 * and so does not execute inline <script> tags).
 */
(function () {
  var configured = false;

  function configureMermaid() {
    if (configured || typeof mermaid === 'undefined') return;
    try {
      mermaid.initialize({
        startOnLoad: false,
        // 'loose' lets us include simple HTML like <br/> in node labels.
        securityLevel: 'loose',
        theme: 'base',
        themeVariables: {
          primaryColor: '#ffffff',
          primaryBorderColor: '#1B2D4F',
          primaryTextColor: '#1B2D4F',
          lineColor: '#3CA53C',
          tertiaryColor: '#f5f8fb',
          fontFamily: 'Inter, Helvetica Neue, sans-serif',
          fontSize: '16px'
        }
      });
      configured = true;
    } catch (e) {
      console.warn('mermaid.initialize failed', e);
    }
  }

  function rewriteCodeBlocksToDivs() {
    // marked.js (the markdown parser reveal.js uses) renders fenced
    // ```mermaid blocks as <pre><code class="language-mermaid">. Mermaid 10
    // expects <div class="mermaid">, so we replace each block. We mark the
    // original element so this runs at most once per block.
    var blocks = document.querySelectorAll('.reveal pre code.language-mermaid:not([data-tia-mermaid])');
    blocks.forEach(function (codeEl) {
      var pre = codeEl.parentElement;
      var div = document.createElement('div');
      div.className = 'mermaid';
      div.textContent = codeEl.textContent;
      if (pre && pre.parentNode) {
        pre.parentNode.replaceChild(div, pre);
      }
      codeEl.setAttribute('data-tia-mermaid', '1');
    });
  }

  function runMermaid() {
    if (typeof mermaid === 'undefined') return;
    configureMermaid();
    rewriteCodeBlocksToDivs();

    var nodes = document.querySelectorAll('.reveal div.mermaid:not([data-processed="true"])');
    if (!nodes.length) return;

    if (typeof mermaid.run === 'function') {
      mermaid.run({ nodes: Array.prototype.slice.call(nodes) }).catch(function (err) {
        console.error('mermaid.run failed', err);
      });
    } else if (typeof mermaid.init === 'function') {
      try { mermaid.init(undefined, nodes); }
      catch (e) { console.error('mermaid.init failed', e); }
    } else {
      console.warn('mermaid API not detected (neither run nor init)');
    }
  }

  function wire() {
    if (typeof Reveal === 'undefined') { setTimeout(wire, 50); return; }
    Reveal.addEventListener('ready', runMermaid);
    Reveal.addEventListener('slidechanged', runMermaid);
    Reveal.addEventListener('slidetransitionend', runMermaid);
    // First pass — covers slides already rendered when Reveal becomes ready.
    setTimeout(runMermaid, 0);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wire);
  } else {
    wire();
  }
})();
