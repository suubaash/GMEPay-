import '@testing-library/jest-dom/vitest';

// jsdom 25's Blob does not implement .text() / .arrayBuffer() / .stream();
// production code that reads a Blob would otherwise blow up only under tests.
// Polyfill the readers our app uses via FileReader, which IS implemented.
if (typeof Blob !== 'undefined' && typeof FileReader !== 'undefined') {
  if (typeof Blob.prototype.text !== 'function') {
    Blob.prototype.text = function blobText() {
      return new Promise((resolve, reject) => {
        const fr = new FileReader();
        fr.onload = () => resolve(String(fr.result ?? ''));
        fr.onerror = () => reject(fr.error ?? new Error('FileReader failed'));
        fr.readAsText(this);
      });
    };
  }
  if (typeof Blob.prototype.arrayBuffer !== 'function') {
    Blob.prototype.arrayBuffer = function blobArrayBuffer() {
      return new Promise((resolve, reject) => {
        const fr = new FileReader();
        fr.onload = () => resolve(fr.result);
        fr.onerror = () => reject(fr.error ?? new Error('FileReader failed'));
        fr.readAsArrayBuffer(this);
      });
    };
  }
}
