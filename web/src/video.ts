import { BufferTarget, CanvasSource, Mp4OutputFormat, Output, Quality } from 'mediabunny';
import { frameAtElapsedSeconds, OUTRO_SECONDS } from './animation';
import { drawFrame } from './renderer';
import type { PreparedJourney } from './types';

export interface ExportOptions {
  durationSeconds: number;
  title: string;
  periodLabel: string;
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

export function hasVideoEncoder(): boolean {
  return typeof globalThis.VideoEncoder !== 'undefined';
}

export async function canCreateMp4(width = 480, height = 480): Promise<boolean> {
  if (!hasVideoEncoder()) return false;
  try {
    const result = await VideoEncoder.isConfigSupported({
      codec: 'avc1.42001f',
      width,
      height,
      bitrate: 2_500_000,
      framerate: 24,
      hardwareAcceleration: 'no-preference',
    });
    return result.supported === true;
  } catch {
    return false;
  }
}

export function isMp4(buffer: ArrayBuffer): boolean {
  if (buffer.byteLength < 12) return false;
  const bytes = new Uint8Array(buffer, 4, 8);
  return String.fromCharCode(...bytes).startsWith('ftyp');
}

export async function createJourneyMp4(
  canvas: HTMLCanvasElement,
  journey: PreparedJourney,
  options: ExportOptions,
): Promise<Blob> {
  if (!hasVideoEncoder()) {
    throw new Error('This browser cannot create MP4 video. Use Safari 16.4 or newer.');
  }

  const fps = 24;
  const frameDuration = 1 / fps;
  const journeyFrameCount = Math.max(1, Math.round(options.durationSeconds * fps));
  const outroFrameCount = Math.round(OUTRO_SECONDS * fps);
  const frameCount = journeyFrameCount + outroFrameCount;
  const target = new BufferTarget();
  const output = new Output({
    format: new Mp4OutputFormat({ fastStart: 'in-memory' }),
    target,
  });
  const source = new CanvasSource(canvas, {
    codec: 'avc',
    fullCodecString: 'avc1.42001f',
    quality: new Quality({ bitrate: 2_500_000 }),
    keyFrameInterval: 1,
    hardwareAcceleration: 'no-preference',
  });
  output.addVideoTrack(source, { frameRate: fps });
  output.setMetadataTags({ title: options.title });
  await output.start();

  for (let frame = 0; frame < frameCount; frame += 1) {
    if (options.signal?.aborted) {
      await output.cancel();
      throw new DOMException('Video creation was cancelled.', 'AbortError');
    }
    const animationFrame = frame < journeyFrameCount
      ? {
        journeyProgress: journeyFrameCount === 1 ? 1 : frame / (journeyFrameCount - 1),
        outroProgress: 0,
      }
      : frameAtElapsedSeconds(
        options.durationSeconds + (frame - journeyFrameCount) / fps,
        options.durationSeconds,
      );
    drawFrame(canvas, journey, animationFrame, options.title, options.periodLabel);
    await source.add(frame * frameDuration, frameDuration, { keyFrame: frame % fps === 0 });
    options.onProgress?.((frame + 1) / frameCount);
  }

  await output.finalize();
  if (!target.buffer) throw new Error('The video encoder did not produce an MP4 file.');
  if (!isMp4(target.buffer)) throw new Error('The video encoder produced an invalid MP4 file.');
  return new Blob([target.buffer], { type: 'video/mp4' });
}
