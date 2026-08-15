function urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding).replace(/\-/g, '+').replace(/_/g, '/');
    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; ++i) {
        outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
}

async function subscribePush() {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
        console.log('Push not supported');
        return;
    }
    try {
        const reg = await navigator.serviceWorker.register('/js/service-worker.js');
        const sub = await reg.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlBase64ToUint8Array('BLrXSeSFooC3M926Z4JKDI5KFP9_eQ4UBTECVv6--H-hDrcPSBv-qRmilQxywzLw7hRO5qrLiciyeaU3dn4che8')
        });
        const key = sub.getKey('p256dh');
        const token = sub.getKey('auth');
        await fetch('/api/push/subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                endpoint: sub.endpoint,
                p256dh: btoa(String.fromCharCode.apply(null, new Uint8Array(key))),
                auth: btoa(String.fromCharCode.apply(null, new Uint8Array(token))),
                userAgent: navigator.userAgent
            })
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
