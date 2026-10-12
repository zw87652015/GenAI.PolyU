package hk.edu.polyu.genai

/**
 * The PolyU ADFS sign-in page locks the layout to 1920px and disables zoom, so a phone
 * only shows the left-hand illustration. This script rewrites that viewport and stacks
 * the form before first paint. It leaves a normal responsive page alone.
 */
object PortalScripts {
    val SOURCE: String = """
        (function () {
          var STYLE = 'html,body{min-width:0!important;width:100%!important;height:auto!important;overflow-x:hidden!important;background:#fff!important}#fullPage,#brandingWrapper,#branding,#contentWrapper,#content,#header,#workArea,#loginArea,#loginForm,#formsAuthenticationArea,#userNameArea,#passwordArea,#submissionArea,#authArea{float:none!important;position:static!important;width:100%!important;height:auto!important;min-width:0!important;min-height:0!important;margin:0!important;box-sizing:border-box!important}#brandingWrapper,#branding{display:none!important}#contentWrapper{display:block!important;padding:24px 20px 48px!important}#content{padding:0!important;margin:0 auto!important;max-width:420px!important}#header{padding:28px 0 8px!important;margin:0 0 12px!important;text-align:center!important}#companyLogo{max-width:220px!important;width:auto!important;height:auto!important}#loginMessage{font-size:16px!important;line-height:1.4!important;margin:0 0 16px!important}#userNameInput,#passwordInput,input.text{display:block!important;width:100%!important;max-width:100%!important;box-sizing:border-box!important;font-size:16px!important;height:48px!important;padding:8px 12px!important;margin:0 0 12px!important}#submitButton,.submit{display:block!important;width:100%!important;box-sizing:border-box!important;text-align:center!important;font-size:16px!important;line-height:28px!important;padding:10px 16px!important;margin-top:8px!important;min-height:48px!important}#footer,#footerPlaceholder{position:static!important;width:100%!important;height:auto!important;margin-top:24px!important}#footer a{display:inline-block!important;padding:8px 6px!important;font-size:15px!important}';
          var NEXT_VIEWPORT = 'width=device-width, initial-scale=1, minimum-scale=1, viewport-fit=cover';
          var applying = false;

          function head() {
            return document.head || document.getElementsByTagName('head')[0] || document.documentElement;
          }

          function apply() {
            if (applying) return;
            applying = true;
            try {
              var h = head();
              if (!h) return;
              var meta = document.querySelector('meta[name="viewport"]');
              if (meta) {
                var content = meta.getAttribute('content') || '';
                if (/width\s*=\s*\d{4,}px/i.test(content)) {
                  meta.setAttribute('content', NEXT_VIEWPORT);
                }
              }
              if (location.hostname === 'adfs.polyu.edu.hk') {
                var style = document.getElementById('polyu-genai-mobile');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'polyu-genai-mobile';
                  h.appendChild(style);
                }
                if (style.textContent !== STYLE) style.textContent = STYLE;
              }
            } finally {
              applying = false;
            }
          }

          apply();
          if (document.readyState === 'loading') {
            var observer = new MutationObserver(apply);
            observer.observe(document.documentElement || document, { childList: true, subtree: true });
            document.addEventListener('DOMContentLoaded', function () {
              apply();
              observer.disconnect();
            });
          }
        })();
    """.trimIndent()
}
