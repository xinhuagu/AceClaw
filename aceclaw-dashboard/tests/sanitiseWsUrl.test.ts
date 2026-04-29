import { describe, expect, it } from 'vitest';
import { sanitiseWsUrl } from '../src/App';

/**
 * Tests for the WS URL guard at the App boundary. Without this gate,
 * `?ws=localhost:3141/ws` (no protocol) crashes the WebSocket constructor
 * synchronously before any error boundary mounts.
 */

describe('sanitiseWsUrl', () => {
  it('returns null for null/undefined/empty', () => {
    expect(sanitiseWsUrl(null)).toBeNull();
    expect(sanitiseWsUrl(undefined)).toBeNull();
    expect(sanitiseWsUrl('')).toBeNull();
  });

  it('rejects malformed URLs that would crash WebSocket', () => {
    expect(sanitiseWsUrl('localhost:3141/ws')).toBeNull();
    expect(sanitiseWsUrl('foo')).toBeNull();
    expect(sanitiseWsUrl('not a url')).toBeNull();
  });

  it('rejects non-ws schemes that would otherwise round-trip', () => {
    expect(sanitiseWsUrl('http://localhost:3141/ws')).toBeNull();
    expect(sanitiseWsUrl('https://localhost:3141/ws')).toBeNull();
    expect(sanitiseWsUrl('javascript:alert(1)')).toBeNull();
    expect(sanitiseWsUrl('file:///etc/hosts')).toBeNull();
  });

  it('accepts ws:// and wss:// URLs', () => {
    expect(sanitiseWsUrl('ws://localhost:3141/ws')).toBe('ws://localhost:3141/ws');
    expect(sanitiseWsUrl('wss://example.com/path')).toBe('wss://example.com/path');
  });
});
