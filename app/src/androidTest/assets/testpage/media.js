// Starts a looping, silent <video> playing a generated WAV, counting pause() calls (window.pauseCalls).
window.pauseCalls = 0;
(function () {
  var original = HTMLMediaElement.prototype.pause;
  HTMLMediaElement.prototype.pause = function () {
    window.pauseCalls++;
    return original.apply(this, arguments);
  };
})();

function silentWav(seconds) {
  var rate = 8000;
  var n = rate * seconds;
  var view = new DataView(new ArrayBuffer(44 + n));
  function text(offset, s) { for (var i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i)); }
  text(0, 'RIFF'); view.setUint32(4, 36 + n, true); text(8, 'WAVE'); text(12, 'fmt ');
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, rate, true); view.setUint32(28, rate, true); view.setUint16(32, 1, true);
  view.setUint16(34, 8, true); text(36, 'data'); view.setUint32(40, n, true);
  for (var i = 0; i < n; i++) view.setUint8(44 + i, 128);
  return URL.createObjectURL(new Blob([view.buffer], { type: 'audio/wav' }));
}

function startMedia() {
  var media = document.createElement('video');
  media.loop = true;
  media.muted = true;
  media.src = silentWav(1);
  document.documentElement.appendChild(media);
  media.play().catch(function () { /* paused stays observable either way */ });
  return media;
}
