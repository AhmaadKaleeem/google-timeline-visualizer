import { BufferTarget, CanvasSource, Mp4OutputFormat, Output, Quality } from 'mediabunny';
import { frameAtElapsedSeconds, OUTRO_SECONDS } from './animation';
import { AppError } from './errors';
import { drawFrame } from './renderer';
import type { OverlayText } from './renderer';
import type { PreparedJourney } from './types';

export interface ExportOptions {
  durationSeconds: number;
  /** Frozen once for the whole export, so every frame carries the same text. */
  overlay: OverlayText;
  format: ResolvedVideoFormat;
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

export type VideoAspectRatio = 'square' | 'portrait' | 'landscape';
export type VideoFormatKey = `${VideoAspectRatio}-${number}`;
export const VIDEO_SHORT_EDGES = [480, 720, 1080, 1440, 2160] as const;
export const VIDEO_FRAME_RATES = [15, 24, 30, 60, 120] as const;
export type VideoFrameRate = (typeof VIDEO_FRAME_RATES)[number];

export interface VideoFormat {
  key: VideoFormatKey;
  aspectRatio: VideoAspectRatio;
  shortEdge: number;
  width: number;
  height: number;
  frameRate: number;
  bitrate: number;
  /** Probed in order; the first supported string wins. */
  codecCandidates: readonly string[];
}

export interface ResolvedVideoFormat extends VideoFormat {
  codec: string;
}

export const DEFAULT_VIDEO_FORMAT_KEY: VideoFormatKey = 'square-480';

// H.264 macroblocks are 16 by 16, so PicSizeInMbs = ceil(width / 16) * ceil(height / 16).
// A codec string avc1.PPCCLL pins profile_idc, constraint flags and level_idc, and mediabunny
// passes fullCodecString straight to VideoEncoder.configure with no fallback and no level
// derivation. Its own string builder checks only MaxFS and MaxBR, never MaxMBPS, so the levels
// below are picked here against Annex A Table A-1 instead:
//   480x480      900 MB at 24 fps =  21,600 MB/s -> level 3.0 (2.2 allows only 20,250 MB/s)
//   720x720    2,025 MB at 24 fps =  48,600 MB/s -> level 3.1 (3.0 MaxFS is only 1,620)
//   1080x1080  4,624 MB at 24 fps = 110,976 MB/s -> level 3.2 (3.1 MaxFS is only 3,600)
//   1080x1920  8,160 MB at 30 fps = 244,800 MB/s -> level 4.0 (MaxMBPS 245,760, 0.4% spare)
//   1920x1080  8,160 MB at 30 fps = 244,800 MB/s -> level 4.0
// Level 4.1 shares MaxFS and MaxMBPS with 4.0, so the extra headroom candidate is 4.2 (2a).
// Baseline (42) comes first because it is the string already shipping and is implemented by
// every VideoToolbox encoder; High (64) is what mediabunny itself would generate.
function even(value: number): number {
  return Math.floor(value / 2) * 2;
}

function androidBitrate(width: number, height: number, frameRate: number, aspectRatio: VideoAspectRatio): number {
  const shortEdge = Math.min(width, height);
  const legacy = shortEdge === 480 ? (aspectRatio === 'square' ? 2_500_000 : 3_500_000)
    : shortEdge === 720 ? (aspectRatio === 'square' ? 5_000_000 : 7_000_000)
      : shortEdge === 1080 ? (aspectRatio === 'square' ? 8_000_000 : 12_000_000)
        : null;
  const legacyRate = aspectRatio === 'square' ? 24 : 30;
  const calculated = legacy === null
    ? Math.floor(width * height * frameRate * 19 / 100)
    : Math.floor(legacy * frameRate / legacyRate);
  return Math.max(1_500_000, Math.min(40_000_000, calculated));
}

export function buildVideoFormat(
  aspectRatio: VideoAspectRatio,
  shortEdge: number,
  frameRate = aspectRatio === 'square' ? 24 : 30,
): VideoFormat {
  if (!Number.isInteger(shortEdge) || shortEdge < 480 || shortEdge > 2160) {
    throw new RangeError('Video short edge must be a whole number from 480 through 2160.');
  }
  if (!Number.isInteger(frameRate) || frameRate < 15 || frameRate > 120) {
    throw new RangeError('Frame rate must be a whole number from 15 through 120.');
  }
  const longEdge = even(shortEdge * 16 / 9);
  const [width, height] = aspectRatio === 'square' ? [shortEdge, shortEdge]
    : aspectRatio === 'portrait' ? [shortEdge, longEdge] : [longEdge, shortEdge];
  const bitrate = androidBitrate(width, height, frameRate, aspectRatio);
  return {
    key: `${aspectRatio}-${shortEdge}`,
    aspectRatio,
    shortEdge,
    width,
    height,
    frameRate,
    bitrate,
    codecCandidates: codecCandidates(width, height, frameRate, bitrate),
  };
}

/** Maps every size and frame-rate combination to the codec string that works, or null. */
export type VideoFormatSupport = ReadonlyMap<string, string | null>;

export function hasVideoEncoder(): boolean {
  return typeof globalThis.VideoEncoder !== 'undefined';
}

export function videoFormatByKey(key: string): VideoFormat | null {
  return VIDEO_FORMATS.find((format) => format.key === key) ?? null;
}

const AVC_LEVELS = [
  { code: '1f', maxFs: 3_600, maxMbps: 108_000, maxBitrate: 14_000_000 },
  { code: '20', maxFs: 5_120, maxMbps: 216_000, maxBitrate: 20_000_000 },
  { code: '28', maxFs: 8_192, maxMbps: 245_760, maxBitrate: 20_000_000 },
  { code: '2a', maxFs: 8_704, maxMbps: 522_240, maxBitrate: 50_000_000 },
  { code: '32', maxFs: 22_080, maxMbps: 589_824, maxBitrate: 50_000_000 },
  { code: '33', maxFs: 36_864, maxMbps: 983_040, maxBitrate: 50_000_000 },
  { code: '34', maxFs: 36_864, maxMbps: 2_073_600, maxBitrate: 50_000_000 },
] as const;

function codecCandidates(width: number, height: number, frameRate: number, bitrate: number): string[] {
  const macroblocks = Math.ceil(width / 16) * Math.ceil(height / 16);
  const levelIndex = AVC_LEVELS.findIndex((level) => level.maxFs >= macroblocks
    && level.maxMbps >= macroblocks * frameRate
    && level.maxBitrate >= bitrate);
  if (levelIndex < 0) return [];
  const levels = AVC_LEVELS.slice(levelIndex, levelIndex + 2);
  return levels.flatMap((level) => [`avc1.4200${level.code}`, `avc1.6400${level.code}`]);
}

export const VIDEO_FORMATS: readonly VideoFormat[] = (['square', 'portrait', 'landscape'] as const)
  .flatMap((aspectRatio) => VIDEO_SHORT_EDGES.map((shortEdge) => buildVideoFormat(aspectRatio, shortEdge)));

/** Builds a concrete format while preserving the proven legacy configuration exactly. */
export function videoFormatAtFrameRate(
  format: VideoFormat,
  frameRate: number,
): VideoFormat {
  if (frameRate === format.frameRate) return format;
  return buildVideoFormat(format.aspectRatio, format.shortEdge, frameRate);
}

export function videoFormatSupportKey(format: VideoFormat): string {
  return `${format.key}@${format.frameRate}`;
}

export const ALL_VIDEO_FORMATS: readonly VideoFormat[] = VIDEO_FORMATS.flatMap((format) =>
  VIDEO_FRAME_RATES.map((frameRate) => videoFormatAtFrameRate(format, frameRate)));

async function probeCodec(format: VideoFormat, codec: string): Promise<boolean> {
  try {
    const result = await VideoEncoder.isConfigSupported({
      codec,
      width: format.width,
      height: format.height,
      bitrate: format.bitrate,
      framerate: format.frameRate,
      hardwareAcceleration: 'no-preference',
    });
    return result.supported === true;
  } catch {
    return false;
  }
}

async function resolveCodecString(format: VideoFormat): Promise<string | null> {
  for (const codec of format.codecCandidates) {
    if (await probeCodec(format, codec)) return codec;
  }
  return null;
}

export async function probeVideoFormat(format: VideoFormat): Promise<string | null> {
  if (!hasVideoEncoder()) return null;
  return resolveCodecString(format);
}

/**
 * Probes every format once. Formats run in parallel, candidates within a format run in order.
 * Never rejects: a missing VideoEncoder or a throwing isConfigSupported both resolve to null.
 * A browser that fails even 480 by 480 Baseline cannot encode H.264 at all, which the
 * compatibility status already reports, so no format is ever silently swapped for another.
 */
export async function probeVideoFormats(): Promise<VideoFormatSupport> {
  if (!hasVideoEncoder()) {
    return new Map(ALL_VIDEO_FORMATS.map((format) => [videoFormatSupportKey(format), null]));
  }
  const entries = await Promise.all(
    ALL_VIDEO_FORMATS.map(async (format): Promise<[string, string | null]> => [
      videoFormatSupportKey(format),
      await resolveCodecString(format),
    ]),
  );
  return new Map(entries);
}

export function resolveVideoFormat(
  format: VideoFormat,
  support: VideoFormatSupport,
): ResolvedVideoFormat | null {
  const codec = support.get(videoFormatSupportKey(format)) ?? null;
  return codec === null ? null : { ...format, codec };
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
    throw new AppError('errorNoEncoder', 'This browser cannot create MP4 video. Use Safari 16.4 or newer.');
  }

  const { width, height, frameRate: fps, bitrate, codec } = options.format;
  if (canvas.width !== width || canvas.height !== height) {
    throw new AppError('errorCanvasSize', 'The preview is not using the selected video format size.');
  }

  const frameDuration = 1 / fps;
  const frameCount = Math.max(1, Math.round(options.durationSeconds * fps));
  const outroFrameCount = Math.min(Math.round(OUTRO_SECONDS * fps), frameCount - 1);
  const journeyFrameCount = frameCount - outroFrameCount;
  const target = new BufferTarget();
  const output = new Output({
    format: new Mp4OutputFormat({ fastStart: 'in-memory' }),
    target,
  });
  const source = new CanvasSource(canvas, {
    codec: 'avc',
    fullCodecString: codec,
    quality: new Quality({ bitrate }),
    keyFrameInterval: 1,
    hardwareAcceleration: 'no-preference',
  });
  output.addVideoTrack(source, { frameRate: fps });
  output.setMetadataTags({ title: options.overlay.title });
  await output.start();

  // Every failure after start() has to reach output.cancel(): only cancel force-closes
  // the VideoEncoder and drops the samples that fastStart 'in-memory' keeps buffered.
  // A rejected source.add - an encoder that MediaCodec could not allocate reports its
  // error asynchronously, long after isConfigSupported said yes - would otherwise leave
  // the encoder open, and every retry would stack another one on top.
  try {
    for (let frame = 0; frame < frameCount; frame += 1) {
      if (options.signal?.aborted) {
        throw new DOMException('Video creation was cancelled.', 'AbortError');
      }
      const animationFrame = frame < journeyFrameCount
        ? {
          journeyProgress: journeyFrameCount === 1 ? 1 : frame / (journeyFrameCount - 1),
          outroProgress: 0,
        }
        : frameAtElapsedSeconds(
          options.durationSeconds - outroFrameCount / fps + (frame - journeyFrameCount) / fps,
          options.durationSeconds,
        );
      drawFrame(canvas, journey, animationFrame, options.overlay);
      await source.add(frame * frameDuration, frameDuration, { keyFrame: frame % fps === 0 });
      options.onProgress?.((frame + 1) / frameCount);
    }

    await output.finalize();
    if (!target.buffer) {
      throw new AppError('errorEncoderOutput', 'The video encoder did not produce an MP4 file.');
    }
    if (!isMp4(target.buffer)) {
      throw new AppError('errorEncoderInvalid', 'The video encoder produced an invalid MP4 file.');
    }
    return new Blob([target.buffer], { type: 'video/mp4' });
  } catch (error) {
    // cancel() is a no-op once finalize() has run, and its own failure must never
    // replace the error that actually stopped the export.
    await output.cancel().catch(() => undefined);
    throw error;
  }
}
