/* ============================================================
   guard.js — 全局登录守卫
   任意接口返回 HTTP 401 时，自动跳转到登录页。
   ============================================================ */
(function () {
    var orig = window.fetch;
    if (!orig || window.__guardInstalled) return;
    window.__guardInstalled = true;
    window.fetch = function () {
        var args = arguments;
        return orig.apply(this, args).then(function (res) {
            var reqUrl = (typeof args[0] === 'string') ? args[0]
                : (args[0] && args[0].url) ? args[0].url : '';
            var isMeCheck = reqUrl.indexOf('/api/auth/me') !== -1;
            var onGuestPage = /^\/(login|register)?$/.test(location.pathname);
            if (res.status === 401 && !isMeCheck && !onGuestPage) {
                location.href = '/login';
            }
            return res;
        });
    };
})();
