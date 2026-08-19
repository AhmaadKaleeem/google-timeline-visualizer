import { cumulativeDistances, project, unwrapWorldPoints, viewportFor } from './geo';
import type { GeoPoint, PreparedJourney, Viewport, WorldPoint } from './types';

const TILE_TEMPLATE = 'https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png';

function worldToCanvas(point: WorldPoint, viewport: Viewport, size: number): [number, number] {
  return [
    ((point.x - viewport.minX) / (viewport.maxX - viewport.minX)) * size,
    ((point.y - viewport.minY) / (viewport.maxY - viewport.minY)) * size,
  ];
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`Could not load map tile ${url}`));
    image.src = url;
  });
}

async function drawMapBackground(canvas: HTMLCanvasElement, viewport: Viewport): Promise<void> {
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Canvas rendering is unavailable.');
  context.fillStyle = '#f2edf0';
  context.fillRect(0, 0, canvas.width, canvas.height);

  const tileCount = 2 ** viewport.zoom;
  const minTileX = Math.floor(viewport.minX * tileCount);
  const maxTileX = Math.floor(viewport.maxX * tileCount);
  const minTileY = Math.max(0, Math.floor(viewport.minY * tileCount));
  const maxTileY = Math.min(tileCount - 1, Math.floor(viewport.maxY * tileCount));
  const tasks: Promise<void>[] = [];

  for (let tileX = minTileX; tileX <= maxTileX; tileX += 1) {
    for (let tileY = minTileY; tileY <= maxTileY; tileY += 1) {
      const wrappedX = ((tileX % tileCount) + tileCount) % tileCount;
      const url = TILE_TEMPLATE.replace('{z}', String(viewport.zoom))
        .replace('{x}', String(wrappedX))
        .replace('{y}', String(tileY));
      tasks.push(
        loadImage(url).then((image) => {
          const worldX = tileX / tileCount;
          const worldY = tileY / tileCount;
          const [left, top] = worldToCanvas({ x: worldX, y: worldY }, viewport, canvas.width);
          const width = (1 / tileCount / (viewport.maxX - viewport.minX)) * canvas.width;
          const height = (1 / tileCount / (viewport.maxY - viewport.minY)) * canvas.height;
          context.drawImage(image, left, top, width, height);
        }).catch(() => undefined),
      );
    }
  }
  await Promise.all(tasks);
}

export async function prepareJourney(points: GeoPoint[], size = 480): Promise<PreparedJourney> {
  if (points.length < 2) throw new Error('Select a period containing at least two location points.');
  const worldPoints = unwrapWorldPoints(points.map((point) => project(point.latitude, point.longitude)));
  const viewport = viewportFor(worldPoints, size);
  const background = document.createElement('canvas');
  background.width = size;
  background.height = size;
  await drawMapBackground(background, viewport);
  const distances = cumulativeDistances(points);
  return {
    points,
    worldPoints,
    cumulativeDistanceKm: distances,
    totalDistanceKm: distances.at(-1) ?? 0,
    viewport,
    background,
  };
}

function pointAtProgress(journey: PreparedJourney, progress: number): { point: WorldPoint; completedIndex: number } {
  const target = journey.totalDistanceKm * Math.max(0, Math.min(1, progress));
  let to = journey.cumulativeDistanceKm.findIndex((distance) => distance >= target);
  if (to < 0) to = journey.cumulativeDistanceKm.length - 1;
  if (to === 0) return { point: journey.worldPoints[0], completedIndex: 0 };
  const from = to - 1;
  const segment = journey.cumulativeDistanceKm[to] - journey.cumulativeDistanceKm[from];
  const fraction = segment <= 0 ? 0 : (target - journey.cumulativeDistanceKm[from]) / segment;
  return {
    point: {
      x: journey.worldPoints[from].x + (journey.worldPoints[to].x - journey.worldPoints[from].x) * fraction,
      y: journey.worldPoints[from].y + (journey.worldPoints[to].y - journey.worldPoints[from].y) * fraction,
    },
    completedIndex: from,
  };
}

function strokeRoute(
  context: CanvasRenderingContext2D,
  points: WorldPoint[],
  head: WorldPoint,
  viewport: Viewport,
  size: number,
): void {
  if (points.length === 0) return;
  context.beginPath();
  points.forEach((point, index) => {
    const [x, y] = worldToCanvas(point, viewport, size);
    if (index === 0) context.moveTo(x, y);
    else context.lineTo(x, y);
  });
  const [headX, headY] = worldToCanvas(head, viewport, size);
  context.lineTo(headX, headY);
  context.stroke();
}

export function drawFrame(
  canvas: HTMLCanvasElement,
  journey: PreparedJourney,
  progress: number,
  title: string,
  periodLabel: string,
): void {
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Canvas rendering is unavailable.');
  const size = canvas.width;
  context.clearRect(0, 0, size, size);
  context.drawImage(journey.background, 0, 0, size, size);

  const current = pointAtProgress(journey, progress);
  context.lineCap = 'round';
  context.lineJoin = 'round';
  const traveled = journey.worldPoints.slice(0, current.completedIndex + 1);
  context.strokeStyle = 'rgba(233, 0, 100, 0.34)';
  context.lineWidth = 5;
  strokeRoute(context, traveled, current.point, journey.viewport, size);

  const currentDistance = journey.totalDistanceKm * Math.max(0, Math.min(1, progress));
  const recentStartDistance = Math.max(0, currentDistance - Math.max(80, journey.totalDistanceKm * 0.16));
  const recentStartIndex = Math.max(
    0,
    journey.cumulativeDistanceKm.findIndex((distance) => distance >= recentStartDistance),
  );
  context.strokeStyle = '#e90064';
  context.lineWidth = 8;
  strokeRoute(
    context,
    journey.worldPoints.slice(recentStartIndex, current.completedIndex + 1),
    current.point,
    journey.viewport,
    size,
  );
  const [headX, headY] = worldToCanvas(current.point, journey.viewport, size);

  context.shadowColor = 'rgba(36, 25, 29, 0.35)';
  context.shadowBlur = 10;
  context.fillStyle = '#24191d';
  context.beginPath();
  context.arc(headX, headY, 10, 0, Math.PI * 2);
  context.fill();
  context.shadowBlur = 0;
  context.strokeStyle = '#e90064';
  context.lineWidth = 5;
  context.beginPath();
  context.arc(headX, headY, 16, 0, Math.PI * 2);
  context.stroke();

  const gradient = context.createLinearGradient(0, 0, 0, 120);
  gradient.addColorStop(0, 'rgba(255, 248, 250, 0.97)');
  gradient.addColorStop(1, 'rgba(255, 248, 250, 0)');
  context.fillStyle = gradient;
  context.fillRect(0, 0, size, 130);
  context.textAlign = 'center';
  context.fillStyle = '#24191d';
  context.font = '700 25px -apple-system, BlinkMacSystemFont, sans-serif';
  context.fillText(title || 'My Journey', size / 2, 42, size - 48);
  context.fillStyle = '#5c4b52';
  context.font = '16px -apple-system, BlinkMacSystemFont, sans-serif';
  context.fillText(periodLabel, size / 2, 69);

  context.textAlign = 'right';
  context.fillStyle = 'rgba(36, 25, 29, 0.78)';
  context.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
  context.fillText('© OpenStreetMap contributors  © CARTO', size - 8, size - 8);
}
