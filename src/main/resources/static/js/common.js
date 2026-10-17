/* ============================================================
   common.js — 网页端公共工具（API 封装 / Toast / 登录检查）
   ============================================================ */

function showToast(msg, ms) {
    ms = ms || 2600;
    var t = document.getElementById('app-toast');
    if (!t) {
        t = document.createElement('div');
        t.id = 'app-toast';
        t.style.cssText = 'position:fixed;right:24px;bottom:24px;z-index:9999;' +
            'background:#263a2e;color:#fff;padding:12px 18px;border-radius:12px;' +
            'box-shadow:0 10px 30px rgba(0,0,0,.25);font-size:14px;display:none;';
        document.body.appendChild(t);
    }
    t.textContent = msg;
    t.style.display = 'block';
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.style.display = 'none'; }, ms);
}

async function apiFetch(url, options) {
    var opts = Object.assign({ headers: { 'Content-Type': 'application/json' } }, options || {});
    if (opts.body && typeof opts.body !== 'string') opts.body = JSON.stringify(opts.body);
    var res = await fetch(url, opts);
    if (res.status === 401) {
        location.href = '/login';
        throw new Error('未登录');
    }
    return res;
}

function requireLogin(cb) {
    fetch('/api/auth/me').then(function (r) { return r.json(); }).then(function (x) {
        if (x.code === 200) {
            if (cb) cb(x.data);
        } else {
            location.href = '/login';
        }
    }).catch(function () { location.href = '/login'; });
}

function logout() {
    fetch('/api/auth/logout', { method: 'POST' }).then(function () { location.href = '/login'; });
}

/* 统一补充 ICP 与公安备案页脚，已有备案链接的页面会自动跳过。 */
(function ensureBeianFooter() {
    var ICP_TEXT = '苏ICP备2026075056号-1';
    var ICP_URL = 'https://beian.miit.gov.cn/';
    var POLICE_TEXT = '苏公网安备32062102001471号';
    var POLICE_URL = 'https://beian.mps.gov.cn/#/query/webSearch?code=32062102001471';
    var STYLE_ID = 'site-beian-footer-style';

    function ensureStyles() {
        if (document.getElementById(STYLE_ID)) return;

        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = [
            '.site-beian{display:flex;flex-direction:column;align-items:center;gap:4px;' +
                'margin-top:8px;text-align:center;}',
            '.site-beian__item{display:inline-flex;align-items:center;justify-content:center;' +
                'gap:6px;color:inherit;font:inherit;line-height:inherit;white-space:nowrap;' +
                'text-decoration:none;}',
            '.site-beian__icon{display:block;width:18px;height:18px;flex:0 0 18px;object-fit:contain;}',
            '#site-beian-footer{margin:20px auto 18px;padding:0 16px;color:#6b7280;' +
                'font-size:12px;line-height:1.6;}',
            '#site-beian-footer a:hover{text-decoration:underline;}'
        ].join('');
        document.head.appendChild(style);
    }

    function render() {
        ensureStyles();
        if (document.querySelector('a[href*="beian.miit.gov.cn"]') &&
            document.querySelector('a[href*="beian.mps.gov.cn"]')) return;

        var footer = document.createElement('div');
        footer.id = 'site-beian-footer';
        footer.className = 'site-beian';
        footer.setAttribute('aria-label', '网站备案信息');

        function createLink(text, href, withIcon) {
            var link = document.createElement('a');
            link.className = 'site-beian__item';
            link.href = href;
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            if (withIcon) {
                var icon = document.createElement('img');
                icon.className = 'site-beian__icon';
                icon.src = '/images/public-security-badge.png';
                icon.alt = '';
                link.appendChild(icon);
            }
            link.appendChild(document.createTextNode(text));
            return link;
        }

        footer.appendChild(createLink(POLICE_TEXT, POLICE_URL, true));
        footer.appendChild(createLink(ICP_TEXT, ICP_URL, false));
        document.body.appendChild(footer);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', render);
    } else {
        render();
    }
})();

/* toast 别名（兼容旧页面） */
function toast(msg, ms) {
    showToast(msg, ms);
}

/* ============================================================
   回答来源引用（RAG sources）
   ============================================================ */

function ensureSourceStyles() {
    if (document.getElementById('msg-sources-style')) return;
    var style = document.createElement('style');
    style.id = 'msg-sources-style';
    style.textContent = [
        '.msg-sources{margin-top:8px;max-width:70%;background:#f4faf6;' +
            'border:1px solid #d9ebe0;border-radius:12px;padding:8px 12px;' +
            'font-size:12px;line-height:1.55;color:#3f5a4b;box-sizing:border-box;}',
        '.msg-sources-label{font-size:11px;font-weight:600;color:#6b8576;margin-bottom:4px;}',
        '.source-list{list-style:none;margin:0;padding:0;display:flex;' +
            'flex-direction:column;gap:3px;}',
        '.source-item{display:flex;align-items:baseline;gap:6px;}',
        '.source-index{color:#2f8f5b;font-weight:700;flex:0 0 auto;}',
        '.source-title{word-break:break-word;}',
        '@media (max-width:720px){.msg-sources{max-width:85%;}}'
    ].join('');
    document.head.appendChild(style);
}

/**
 * 把后端下发的 [{sourceId, title}] 构建成来源块。
 * 所有文本都通过 textContent 写入，避免知识标题里的 HTML 被执行。
 * 空列表或非法数据返回 null。
 */
function buildSourcesElement(sources) {
    if (!Array.isArray(sources) || !sources.length) return null;
    var seen = {};
    var items = [];
    for (var i = 0; i < sources.length; i++) {
        var s = sources[i];
        if (!s || typeof s !== 'object') continue;
        var sourceId = typeof s.sourceId === 'string' ? s.sourceId.trim() : '';
        var title = typeof s.title === 'string' ? s.title.trim() : '';
        var key = sourceId || title;
        if (!key || seen[key]) continue;
        seen[key] = true;
        items.push(title || sourceId);
    }
    if (!items.length) return null;

    ensureSourceStyles();
    var box = document.createElement('div');
    box.className = 'msg-sources';
    box.setAttribute('aria-label', '本次回答的知识来源');

    var label = document.createElement('div');
    label.className = 'msg-sources-label';
    label.textContent = '参考来源';
    box.appendChild(label);

    var list = document.createElement('ol');
    list.className = 'source-list';
    for (var j = 0; j < items.length; j++) {
        var li = document.createElement('li');
        li.className = 'source-item';
        var index = document.createElement('span');
        index.className = 'source-index';
        index.textContent = '[' + (j + 1) + ']';
        var titleEl = document.createElement('span');
        titleEl.className = 'source-title';
        titleEl.textContent = items[j];
        li.appendChild(index);
        li.appendChild(titleEl);
        list.appendChild(li);
    }
    box.appendChild(list);
    return box;
}

function renderSourcesBlock(container, sources) {
    if (!container) return;
    var old = container.querySelector('.msg-sources');
    if (old) old.remove();
    var element = buildSourcesElement(sources);
    if (element) container.appendChild(element);
}

/* 图片压缩：读取后缩放到 maxSize 内并转 WebP（失败回退 JPEG） */
function compressImage(file, maxSize, quality) {
    maxSize = maxSize || 1280;
    quality = quality || 0.82;
    return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onerror = function () { reject(new Error('读取图片失败')); };
        reader.onload = function (ev) {
            var img = new Image();
            img.onerror = function () { reject(new Error('图片解析失败')); };
            img.onload = function () {
                var scale = Math.min(1, maxSize / Math.max(img.width, img.height));
                var cw = Math.max(1, Math.round(img.width * scale));
                var ch = Math.max(1, Math.round(img.height * scale));
                var canvas = document.createElement('canvas');
                canvas.width = cw;
                canvas.height = ch;
                canvas.getContext('2d').drawImage(img, 0, 0, cw, ch);
                var dataUrl = canvas.toDataURL('image/webp', quality);
                if (dataUrl.indexOf('data:image/webp') !== 0) {
                    dataUrl = canvas.toDataURL('image/jpeg', quality);
                }
                resolve(dataUrl.split(',')[1]);
            };
            img.src = ev.target.result;
        };
        reader.readAsDataURL(file);
    });
}
