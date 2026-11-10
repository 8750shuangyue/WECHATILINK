async function subscribePush() {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
        console.log('Push not supported');
        return;
    }
    try {
        const reg = await UI.sw.getRegistration();
        if (!reg) return;
        let sub = await reg.pushManager.getSubscription();
        if (!sub) {
            sub = await reg.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: UI.sw.applicationServerKey()
            });
        }
        await UI.apiJson('/api/push/subscribe', {
            method: 'POST',
            body: UI.sw.payload(sub)
        });
        console.log('Push subscribed');
    } catch (e) {
        console.error('Push subscription failed:', e);
    }
}

// Auto-subscribe on page load
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', subscribePush);
} else {
    subscribePush();
}
