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

/* toast 别名（兼容旧页面） */
function toast(msg, ms) {
    showToast(msg, ms);
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
