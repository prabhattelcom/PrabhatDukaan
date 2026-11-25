
const CACHE_NAME = "prabhat-dukaan-v3";

const FILES_TO_CACHE = [
  "./customer.html",
  "./manifest.json"
];

// ऐप पहली बार इंस्टॉल होने पर जरूरी फाइलें सेव होंगी
self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(FILES_TO_CACHE);
    })
  );

  self.skipWaiting();
});

// पुराने Cache हटेंगे
self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys
          .filter((key) => key !== CACHE_NAME)
          .map((key) => caches.delete(key))
      );
    })
  );

  self.clients.claim();
});

// इंटरनेट उपलब्ध हो तो नया पेज लोड होगा,
// इंटरनेट न हो तो सेव किया हुआ पेज खुलेगा
self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;

  const url = new URL(event.request.url);

  // Supabase और दूसरे बाहरी सर्वर को Cache नहीं करना
  if (url.origin !== self.location.origin) return;

  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put("./customer.html", copy);
          });
          return response;
        })
        .catch(() => caches.match("./customer.html"))
    );
  }
});
