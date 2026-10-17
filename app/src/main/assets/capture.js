// HomeCast capture hook. Runs at document start in every frame and reports media URLs to the app through
// HomeCast.postMessage(JSON string). It only observes: it never changes requests, responses or the page, except that
// it pauses the page's own <video>/<audio> when the app asks (the video is playing on the TV instead).
(function () {
  'use strict';
  if (window.__homeCastCapture) return;
  window.__homeCastCapture = true;

  var MAX_BODY = 2 * 1024 * 1024;
  var POLL_MILLIS = 2000;
  var PAUSE = 'homecast:pause';
  var sent = {};

  function bridge() {
    var b = window.HomeCast;
    return b && typeof b.postMessage === 'function' ? b : null;
  }

  // Posts each message once per key; a track is posted again when its showing state changes.
  function post(msg) {
    var key = msg.type + ' ' + msg.url;
    var state = msg.type === 'track' ? (msg.showing ? 'showing' : 'hidden') : 'seen';
    if (sent[key] === state) return;
    var b = bridge();
    if (!b) return;
    sent[key] = state;
    msg.frame = location.href;
    try { b.postMessage(JSON.stringify(msg)); } catch (e) { /* ignore */ }
  }

  function absolute(url) {
    try { return new URL(String(url), location.href).href; } catch (e) { return null; }
  }

  function isWeb(url) {
    return typeof url === 'string' && /^https?:\/\//i.test(url);
  }

  // text/*, application/*mpegurl* or no Content-Type at all.
  function inspectable(contentType) {
    if (!contentType) return true;
    var type = String(contentType).toLowerCase();
    return type.indexOf('text/') === 0 || (type.indexOf('application/') === 0 && type.indexOf('mpegurl') >= 0);
  }

  function tooLarge(length) {
    var n = parseInt(length, 10);
    return !isNaN(n) && n >= MAX_BODY;
  }

  function isPlaylist(text) {
    return typeof text === 'string' && text.replace(/^﻿/, '').replace(/^\s+/, '').indexOf('#EXTM3U') === 0;
  }

  var VTT_SEGMENT = /^[^#\s][^\n]*\.(vtt|webvtt)(\?[^\n]*)?\s*$/im;
  var SUBTITLE_RENDITION = /#EXT-X-MEDIA:[^\n]*TYPE=SUBTITLES[^\n]*/g;
  var URI_ATTRIBUTE = /URI="([^"]+)"/;

  function report(url, text) {
    if (!isWeb(url) || !isPlaylist(text)) return;
    var master = text.indexOf('#EXT-X-STREAM-INF') >= 0;
    // A subtitle rendition (its segments are WebVTT): not a video, and its segments are not subtitle files.
    if (!master && VTT_SEGMENT.test(text)) {
      post({ type: 'subplaylist', url: url });
      return;
    }
    post({ type: 'hls', url: url, master: master });
    var renditions = master ? text.match(SUBTITLE_RENDITION) || [] : [];
    for (var i = 0; i < renditions.length; i++) {
      var uri = URI_ATTRIBUTE.exec(renditions[i]);
      var rendition = null;
      try { rendition = uri ? new URL(uri[1], url).href : null; } catch (e) { /* ignore */ }
      if (isWeb(rendition)) post({ type: 'subplaylist', url: rendition });
    }
  }

  // Reads at most MAX_BODY characters of a cloned body, giving up as soon as it cannot be a playlist.
  function readPrefix(response) {
    if (!response.body || !response.body.getReader || typeof TextDecoder === 'undefined') {
      return response.text().then(function (t) { return t.length < MAX_BODY ? t : null; });
    }
    var reader = response.body.getReader();
    var decoder = new TextDecoder();
    var text = '';
    function next() {
      return reader.read().then(function (chunk) {
        if (chunk.done) return text + decoder.decode();
        text += decoder.decode(chunk.value, { stream: true });
        if (text.length >= MAX_BODY || (text.length >= 16 && !isPlaylist(text))) {
          reader.cancel();
          return isPlaylist(text) ? null : '';
        }
        return next();
      });
    }
    return next();
  }

  if (typeof window.fetch === 'function') {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var url = absolute(input && typeof input === 'object' && 'url' in input ? input.url : input);
      var result = originalFetch.apply(this, arguments);
      try {
        result.then(function (response) {
          try {
            var headers = response.headers;
            if (!response.ok || !inspectable(headers.get('content-type')) || tooLarge(headers.get('content-length'))) return;
            readPrefix(response.clone()).then(function (text) { report(url, text); }, function () {});
          } catch (e) { /* ignore */ }
        }, function () {});
      } catch (e) { /* ignore */ }
      return result;
    };
  }

  if (window.XMLHttpRequest) {
    var proto = window.XMLHttpRequest.prototype;
    var originalOpen = proto.open;
    var originalSend = proto.send;
    proto.open = function (method, url) {
      this.__homeCastUrl = absolute(url);
      return originalOpen.apply(this, arguments);
    };
    proto.send = function () {
      var xhr = this;
      try {
        xhr.addEventListener('load', function () {
          try {
            if (xhr.status < 200 || xhr.status >= 300) return;
            if (xhr.responseType !== '' && xhr.responseType !== 'text') return;
            if (!inspectable(xhr.getResponseHeader('content-type'))) return;
            var text = xhr.responseText;
            if (text.length < MAX_BODY) report(xhr.__homeCastUrl, text);
          } catch (e) { /* ignore */ }
        });
      } catch (e) { /* ignore */ }
      return originalSend.apply(this, arguments);
    };
  }

  function scan() {
    try {
      var videos = document.getElementsByTagName('video');
      for (var i = 0; i < videos.length; i++) {
        var src = videos[i].currentSrc || videos[i].src;
        if (isWeb(src)) post({ type: 'video', url: src });
      }
      var tracks = document.getElementsByTagName('track');
      for (var j = 0; j < tracks.length; j++) {
        var track = tracks[j];
        // Chapters, descriptions and metadata (e.g. thumbnail sprites) are not subtitles.
        var kind = String(track.kind || 'subtitles').toLowerCase();
        if (kind !== 'subtitles' && kind !== 'captions') continue;
        var trackSrc = absolute(track.getAttribute('src') || '');
        var showing = !!(track.track && track.track.mode === 'showing');
        if (isWeb(trackSrc)) {
          post({ type: 'track', url: trackSrc, label: track.label || '', lang: track.srclang || '', showing: showing });
        }
      }
    } catch (e) { /* ignore */ }
  }

  function pauseAll() {
    try {
      var media = document.querySelectorAll('video, audio');
      for (var i = 0; i < media.length; i++) {
        try { media[i].pause(); } catch (e) { /* ignore */ }
      }
    } catch (e) { /* ignore */ }
  }

  // The app sends PAUSE to every frame that said hello, when the user plays the video on the TV.
  (function listen() {
    var b = bridge();
    if (!b) return;
    try {
      var onMessage = function (event) { if (event && event.data === PAUSE) pauseAll(); };
      if (typeof b.addEventListener === 'function') b.addEventListener('message', onMessage); else b.onmessage = onMessage;
      b.postMessage(JSON.stringify({ type: 'hello' }));
    } catch (e) { /* ignore */ }
  })();

  // No polling while the page is hidden (another screen of the app, or the app in the background).
  setInterval(function () { if (!document.hidden) scan(); }, POLL_MILLIS);
  document.addEventListener('DOMContentLoaded', scan);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) scan(); });
})();
