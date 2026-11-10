/* ============================================================
   common.js — 网页端公共工具（UI 命名空间 / API / Toast / 登录检查）
   保持旧全局函数兼容，页面可继续直接调用 showToast/apiFetch 等。
   ============================================================ */
(function () {
    'use strict';

    var TOAST_ID = 'app-toast';
    var TOAST_TYPES = {success: 1, error: 1, warning: 1, info: 1};
    var mePromise = null;
    var meData = null;
    var redirecting = false;
    var busyState = new WeakMap();

    function redirectToLogin() {
        if (redirecting) return;
        redirecting = true;
        location.href = '/login';
        setTimeout(function () { redirecting = false; }, 2000);
    }

    function escapeHtml(value) {
        var map = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};
        return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
            return map[ch];
        });
    }

    function toast(message, opts) {
        var type = 'info';
        var ms = 2600;
        if (typeof opts === 'string' && TOAST_TYPES[opts]) {
            type = opts;
        } else if (typeof opts === 'number') {
            ms = opts;
        } else if (opts && typeof opts === 'object') {
            if (typeof opts.type === 'string' && TOAST_TYPES[opts.type]) type = opts.type;
            if (typeof opts.ms === 'number') ms = opts.ms;
        }

        var el = document.getElementById(TOAST_ID);
        if (!el) {
            el = document.createElement('div');
            el.id = TOAST_ID;
            el.className = 'ui-toast';
            el.setAttribute('role', 'status');
            el.setAttribute('aria-live', 'polite');
            document.body.appendChild(el);
        }
        el.className = 'ui-toast ui-toast--' + type;
        el.textContent = String(message == null ? '' : message);
        void el.offsetWidth;
        el.classList.add('is-visible');
        clearTimeout(el._timer);
        el._timer = setTimeout(function () {
            el.classList.remove('is-visible');
        }, ms);
    }

    function hasHeader(headers, name) {
        var lower = name.toLowerCase();
        for (var key in headers) {
            if (Object.prototype.hasOwnProperty.call(headers, key) && key.toLowerCase() === lower) {
                return true;
            }
        }
        return false;
    }

    function apiFetch(url, options) {
        var opts = Object.assign({credentials: 'same-origin'}, options || {});
        var headers = Object.assign({}, opts.headers || {});
        var body = opts.body;
        var isForm = (typeof FormData !== 'undefined' && body instanceof FormData) ||
            (typeof Blob !== 'undefined' && body instanceof Blob) ||
            (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams);

        if (body && typeof body === 'object' && !isForm) {
            body = JSON.stringify(body);
            if (!hasHeader(headers, 'Content-Type')) {
                headers['Content-Type'] = 'application/json';
            }
        }
        if (!hasHeader(headers, 'Accept')) {
            headers['Accept'] = 'application/json';
        }
        opts.headers = headers;
        opts.body = body;

        return fetch(url, opts).then(function (res) {
            if (res.status === 401) {
                if (opts.authRedirect !== false) redirectToLogin();
                throw new Error('未登录');
            }
            return res;
        });
    }

    function apiJson(url, options) {
        return apiFetch(url, options).then(function (res) {
            return res.text().then(function (text) {
                if (!text) return null;
                try {
                    return JSON.parse(text);
                } catch (e) {
                    throw new Error('响应不是合法 JSON');
                }
            });
        });
    }

    function me() {
        if (mePromise) return mePromise;
        mePromise = fetch('/api/auth/me', {cache: 'no-store', credentials: 'same-origin'})
            .then(function (res) { return res.json(); })
            .then(function (data) {
                if (data && data.code === 200) {
                    meData = data.data || null;
                } else {
                    meData = null;
                    mePromise = null;
                }
                return data;
            })
            .catch(function (err) {
                mePromise = null;
                meData = null;
                throw err;
            });
        return mePromise;
    }

    function requireLogin(cb) {
        return me().then(function (data) {
            if (data && data.code === 200 && data.data) {
                if (cb) cb(data.data);
                return data.data;
            }
            redirectToLogin();
            return null;
        }).catch(function () {
            redirectToLogin();
            return null;
        });
    }

    function logout() {
        mePromise = null;
        meData = null;
        return fetch('/api/auth/logout', {method: 'POST'})
            .catch(function () { /* 忽略网络错误，仍然跳转登录页 */ })
            .then(function () { location.href = '/login'; });
    }

    function setBusy(target, busy, options) {
        var el = typeof target === 'string' ? document.getElementById(target) : target;
        if (!el) return function () {};
        if (busy) {
            if (!busyState.has(el)) {
                busyState.set(el, {disabled: !!el.disabled, text: el.textContent});
            }
            el.disabled = true;
            el.setAttribute('aria-busy', 'true');
            if (options && options.text) el.textContent = options.text;
        } else {
            restoreBusy(el);
        }
        return function () { restoreBusy(el); };
    }

    function restoreBusy(el) {
        var saved = busyState.get(el);
        if (!saved) return;
        el.disabled = saved.disabled;
        el.removeAttribute('aria-busy');
        if (saved.text != null) el.textContent = saved.text;
        busyState.delete(el);
    }

    /* 来源样式已迁入 pages.css，保留空函数兼容旧调用。 */
    function ensureSourceStyles() {}

    function buildSourcesElement(sources, options) {
        if (!Array.isArray(sources) || !sources.length) return null;
        options = options || {};
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
            var rank = (typeof s.rank === 'number' && s.rank > 0) ? s.rank : (i + 1);
            items.push({rank: rank, title: title || sourceId});
        }
        if (!items.length) return null;

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
            var item = items[j];
            var li = document.createElement('li');
            li.className = 'source-item';
            li.setAttribute('data-source-rank', String(item.rank));
            if (options.idPrefix) li.id = options.idPrefix + '-source-' + item.rank;
            var index = document.createElement('span');
            index.className = 'source-index';
            index.textContent = '[' + item.rank + ']';
            var titleEl = document.createElement('span');
            titleEl.className = 'source-title';
            titleEl.textContent = item.title;
            li.appendChild(index);
            li.appendChild(titleEl);
            list.appendChild(li);
        }
        box.appendChild(list);
        return box;
    }

    function renderSourcesBlock(container, sources, options) {
        if (!container) return;
        var old = container.querySelector('.msg-sources');
        if (old) old.remove();
        var element = buildSourcesElement(sources, options);
        if (element) container.appendChild(element);
    }

    function renderAnswerWithCitations(container, text, sources, options) {
        if (!container) return;
        options = options || {};
        var value = text == null ? '' : String(text);
        var rankTitles = {};
        if (Array.isArray(sources)) {
            for (var i = 0; i < sources.length; i++) {
                var s = sources[i];
                if (!s || typeof s !== 'object') continue;
                var rank = (typeof s.rank === 'number' && s.rank > 0) ? s.rank : (i + 1);
                if (rankTitles[rank] === undefined) {
                    var title = typeof s.title === 'string' ? s.title.trim() : '';
                    var sourceId = typeof s.sourceId === 'string' ? s.sourceId.trim() : '';
                    rankTitles[rank] = title || sourceId || '';
                }
            }
        }

        container.textContent = '';
        container.style.whiteSpace = 'pre-wrap';
        var regex = /\[(\d{1,2})\]/g;
        var lastIndex = 0;
        var match;
        while ((match = regex.exec(value)) !== null) {
            if (match.index > lastIndex) {
                container.appendChild(document.createTextNode(value.slice(lastIndex, match.index)));
            }
            var citationRank = parseInt(match[1], 10);
            if (rankTitles[citationRank] !== undefined) {
                container.appendChild(buildCitationBadge(citationRank, rankTitles[citationRank], container));
            } else {
                container.appendChild(document.createTextNode(match[0]));
            }
            lastIndex = regex.lastIndex;
        }
        if (lastIndex < value.length) {
            container.appendChild(document.createTextNode(value.slice(lastIndex)));
        }
    }

    function buildCitationBadge(rank, title, bubble) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'citation-badge';
        button.textContent = '[' + rank + ']';
        button.setAttribute('data-source-rank', String(rank));
        button.setAttribute('aria-label', '查看来源 ' + rank + (title ? '：' + title : ''));
        button.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            scrollToSource(bubble, rank);
        });
        return button;
    }

    function scrollToSource(container, rank) {
        var root = container && container.closest ? container.closest('.msg') : null;
        if (!root) root = document;
        var item = root.querySelector('.source-item[data-source-rank="' + rank + '"]');
        if (!item) return;
        if (item.scrollIntoView) item.scrollIntoView({behavior: 'smooth', block: 'center'});
        item.classList.add('source-highlight');
        setTimeout(function () { item.classList.remove('source-highlight'); }, 1800);
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

    var SW_PATH = '/js/service-worker.js';
    var SW_SCOPE = '/';
    var VAPID_PUBLIC_KEY = 'BLrXSeSFooC3M926Z4JKDI5KFP9_eQ4UBTECVv6--H-hDrcPSBv-qRmilQxywzLw7hRO5qrLiciyeaU3dn4che8';

    function b64ToUint8Array(base64String) {
        var padding = '='.repeat((4 - base64String.length % 4) % 4);
        var base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
        var rawData = window.atob(base64);
        return new Uint8Array([...rawData].map(function (c) { return c.charCodeAt(0); }));
    }

    function subscriptionPayload(sub) {
        var p256dh = sub.getKey('p256dh');
        var auth = sub.getKey('auth');
        return {
            endpoint: sub.endpoint,
            p256dh: btoa(String.fromCharCode.apply(null, new Uint8Array(p256dh))),
            auth: btoa(String.fromCharCode.apply(null, new Uint8Array(auth))),
            userAgent: navigator.userAgent
        };
    }

    function timeoutAfter(ms, value) {
        return new Promise(function (resolve) { setTimeout(function () { resolve(value); }, ms); });
    }

    /* 清理旧 /js/ registration：先迁移订阅，成功后再注销旧注册。 */
    async function reconcileLegacy(reg) {
        if (!navigator.serviceWorker.getRegistrations) return;
        if (!reg || new URL(reg.scope).pathname !== SW_SCOPE) return;
        var regs = await navigator.serviceWorker.getRegistrations();
        var legacy = regs.find(function (r) { return new URL(r.scope).pathname === '/js/'; });
        if (!legacy) return;

        try {
            /* 某些浏览器在旧 worker 未激活时 getSubscription() 会长时间不返回；
               超时则保留旧注册，下次页面加载再试，避免阻塞推送初始化。 */
            var subState = await Promise.race([
                legacy.pushManager.getSubscription()
                    .then(function (sub) { return {ok: true, sub: sub}; })
                    .catch(function () { return {ok: true, sub: null}; }),
                timeoutAfter(3000, {ok: false})
            ]);
            if (!subState.ok) return;
            var oldSub = subState.sub;
            if (!oldSub) {
                await legacy.unregister();
                return;
            }
            var ready = await Promise.race([
                navigator.serviceWorker.ready,
                timeoutAfter(3000, null)
            ]);
            if (!ready) return;
            var newSub = await reg.pushManager.getSubscription();
            if (!newSub) {
                newSub = await reg.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: b64ToUint8Array(VAPID_PUBLIC_KEY)
                });
            }
            var data = await apiJson('/api/push/subscribe', {
                method: 'POST',
                body: subscriptionPayload(newSub)
            });
            if (!data || data.code !== 200) throw new Error('订阅迁移失败');
            await oldSub.unsubscribe();
            await apiJson('/api/push/unsubscribe', {
                method: 'DELETE',
                body: {endpoint: oldSub.endpoint}
            }).catch(function () { /* 旧 endpoint 由推送服务返回 410 后自然失效 */ });
            await legacy.unregister();
        } catch (e) {
            console.warn('旧 Service Worker 订阅迁移失败，保留旧注册等待下次重试', e);
        }
    }

    async function swRegister() {
        if (!('serviceWorker' in navigator)) return null;
        var reg;
        try {
            reg = await navigator.serviceWorker.register(SW_PATH, {scope: SW_SCOPE});
        } catch (e) {
            /* 兼容后端 Service-Worker-Allowed 尚未生效的滚动发布窗口 */
            reg = await navigator.serviceWorker.register(SW_PATH);
        }
        reconcileLegacy(reg).catch(function () { /* 清理失败不阻塞页面 */ });
        return reg;
    }

    async function swGetRegistration() {
        if (!('serviceWorker' in navigator)) return null;
        var reg = await navigator.serviceWorker.getRegistration(SW_SCOPE);
        if (reg) {
            reconcileLegacy(reg).catch(function () { /* 清理失败不阻塞页面 */ });
            return reg;
        }
        return swRegister();
    }

    window.UI = {
        toast: toast,
        apiFetch: apiFetch,
        apiJson: apiJson,
        me: me,
        getMe: me,
        currentUser: function () { return meData; },
        requireLogin: requireLogin,
        logout: logout,
        setBusy: setBusy,
        escapeHtml: escapeHtml,
        escapeAttr: escapeHtml,
        sw: {
            path: SW_PATH,
            scope: SW_SCOPE,
            vapidPublicKey: VAPID_PUBLIC_KEY,
            applicationServerKey: function () { return b64ToUint8Array(VAPID_PUBLIC_KEY); },
            payload: subscriptionPayload,
            register: swRegister,
            getRegistration: swGetRegistration
        }
    };

    /* 旧全局函数兼容层；页面内联 onclick 继续可用。 */
    window.showToast = toast;
    window.toast = toast;
    window.apiFetch = apiFetch;
    window.apiJson = apiJson;
    window.requireLogin = requireLogin;
    window.logout = logout;
    window.buildSourcesElement = buildSourcesElement;
    window.renderSourcesBlock = renderSourcesBlock;
    window.renderAnswerWithCitations = renderAnswerWithCitations;
    window.ensureSourceStyles = ensureSourceStyles;
    window.compressImage = compressImage;

    /* 登录后页面：公共层填充侧栏用户名，避免 shell + 页面各请求一次。 */
    function fillShellUser() {
        if (!document.getElementById('ui-sidebar')) return;
        me().then(function (data) {
            if (data && data.code === 200 && data.data && window.setShellUser) {
                window.setShellUser(data.data.userName || data.data.user || '');
            }
        }).catch(function () { /* 未登录由页面守卫处理 */ });
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', fillShellUser);
    } else {
        fillShellUser();
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
            /* 应用页有左侧栏：备案页脚要落在主内容列里，不能挂在 body 上。
               对话页是满屏布局，页脚放到 .ui-main 末尾，避免挤占聊天高度。 */
            var chatWrap = document.querySelector('.ui-wrap--chat');
            var host = chatWrap
                ? (document.querySelector('.ui-main') || document.body)
                : (document.querySelector('.ui-wrap') || document.querySelector('.ui-main') || document.body);
            host.appendChild(footer);
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', render);
        } else {
            render();
        }
    })();
})();
