/* ============================================================
   shell.js — 登录后页面的共享应用壳
   必须在 <body> 后第一行加载：阻塞解析，先画出侧栏再渲染正文。
   ============================================================ */
(function () {
    'use strict';

    if (document.getElementById('ui-sidebar')) return;

    var NAV = [
        {
            href: '/home', label: '概览',
            icon: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1Z"/>'
        },
        {
            href: '/care', label: '护理助手',
            icon: '<path d="M12 21s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 11c0 5.65-7 10-7 10Z"/>'
        },
        {
            href: '/chat', label: 'AI 对话',
            icon: '<path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9 9 0 0 1-4-.9L3 21l1.9-4.9a8.4 8.4 0 0 1-1-4A8.4 8.4 0 0 1 12 3a8.4 8.4 0 0 1 9 8.5Z"/>'
        },
        {
            href: '/reminders', label: '护理提醒',
            icon: '<path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>'
        },
        {
            href: '/community', label: '养护社区',
            icon: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>'
        },
        {
            href: '/kb', label: '知识库',
            icon: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/>'
        },
        {
            href: '/stats', label: '数据概览',
            icon: '<path d="M3 3v18h18"/><path d="M7 15v3"/><path d="M12 10v8"/><path d="M17 6v12"/>'
        }
    ];

    function normalizePath(p) {
        var path = String(p || '').replace(/\/index\.html$/, '/');
        path = path.replace(/\.html$/, '');
        path = path.replace(/\/+$/, '');
        if (!path) path = '/home';
        return path;
    }

    var current = normalizePath(location.pathname);
    if (current === '/' || current === '/index') current = '/home';

    var links = NAV.map(function (item) {
        var active = item.href === current;
        return '<a class="ui-nav-item' + (active ? ' is-active' : '') + '" href="' + item.href + '"' +
            (active ? ' aria-current="page"' : '') + '>' +
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
            'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + item.icon + '</svg>' +
            '<span>' + item.label + '</span></a>';
    }).join('');

    var html =
        '<aside class="ui-sidebar" id="ui-sidebar">' +
            '<a class="ui-brand" href="/home"><span class="ui-brand-mark" aria-hidden="true"></span>Sekai PetPlant</a>' +
            '<nav class="ui-nav" aria-label="主导航">' + links + '</nav>' +
            '<div class="ui-sidebar-foot">' +
                '<span class="ui-user" id="ui-user"></span>' +
                '<button type="button" class="ui-btn ui-btn-ghost" onclick="logout()">退出登录</button>' +
            '</div>' +
        '</aside>';

    document.body.insertAdjacentHTML('afterbegin', html);
    document.body.classList.add('ui-app');

    window.setShellUser = function (name) {
        var el = document.getElementById('ui-user');
        if (!el) return;
        var value = String(name || '').trim();
        el.textContent = value ? (/^(早上好|上午好|中午好|下午好|晚上好|你好|夜深了)/.test(value) ? value : '你好，' + value) : '';
        el.title = value;
    };

    /* 用户名由 common.js 的 UI.me() 统一填充，避免 shell + 页面重复请求。 */
})();
