// 生成 Pi Web 启动器图标（圆角深色底 + 终端提示符）,无第三方依赖
const zlib = require('zlib');
const fs = require('fs');

let table = null;
function crc32(buf) {
  if (!table) {
    table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}

function png(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const BG = [0x12, 0x15, 0x1b];
const FG = [0x22, 0xd3, 0xee];
const BAR = [0x4a, 0xde, 0x80];

const inRoundRect = (x, y, r) => {
  x = Math.abs(x - 0.5); y = Math.abs(y - 0.5);
  const d = 0.5 - r;
  const qx = Math.max(x - d, 0), qy = Math.max(y - d, 0);
  return Math.hypot(qx, qy) <= r;
};

const segDist = (px, py, ax, ay, bx, by) => {
  const dx = bx - ax, dy = by - ay;
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
};

function render(size) {
  const SS = 4; // 超采样
  const buf = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const px = (x + (sx + 0.5) / SS) / size;
          const py = (y + (sy + 0.5) / SS) / size;
          let col = null, alpha = 0;
          if (inRoundRect(px, py, 0.225)) { col = BG; alpha = 1; }
          if (col) {
            const chev = Math.min(
              segDist(px, py, 0.30, 0.28, 0.53, 0.50),
              segDist(px, py, 0.53, 0.50, 0.30, 0.72)
            );
            if (chev <= 0.045) { col = FG; }
            if (px >= 0.585 && px <= 0.775 && py >= 0.625 && py <= 0.695) { col = BAR; }
          }
          if (col) { r += col[0]; g += col[1]; b += col[2]; a += 1; }
        }
      }
      const n = SS * SS;
      const o = (y * size + x) * 4;
      if (a === 0) { buf[o] = buf[o + 1] = buf[o + 2] = buf[o + 3] = 0; }
      else {
        buf[o] = Math.round(r / a); buf[o + 1] = Math.round(g / a); buf[o + 2] = Math.round(b / a);
        buf[o + 3] = Math.round((a / n) * 255);
      }
    }
  }
  return png(size, size, buf);
}

const sizes = { 'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96, 'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192 };
for (const [dir, size] of Object.entries(sizes)) {
  fs.mkdirSync(`res/${dir}`, { recursive: true });
  fs.writeFileSync(`res/${dir}/ic_launcher.png`, render(size));
}
fs.mkdirSync('res/mipmap-anydpi-v26', { recursive: true });
console.log('icons written');
