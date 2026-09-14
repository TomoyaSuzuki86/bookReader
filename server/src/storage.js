import fs from 'node:fs';
import path from 'node:path';

const read = p => fs.readFileSync(p);
const write = (p,b) => { fs.mkdirSync(path.dirname(p),{recursive:true}); fs.writeFileSync(p,b); };

async function errorDetail(response) {
  const text = await response.text().catch(() => '');
  return text ? `: ${text.slice(0, 500)}` : '';
}

export class PersistentStore {
  constructor({ root, proxyUrl, proxySecret }) {
    this.root = root;
    this.proxyUrl = (proxyUrl || '').replace(/\/$/,'');
    this.proxySecret = proxySecret || '';
    this.remote = Boolean(this.proxyUrl && this.proxySecret);
  }

  localPath(objectPath) {
    return path.join(this.root, objectPath);
  }

  headers(extra={}) {
    return { 'x-bookreader-secret': this.proxySecret, ...extra };
  }

  objectUrl(objectPath) {
    const url = new URL(this.proxyUrl);
    if (objectPath) url.searchParams.set('path', objectPath);
    return url.toString();
  }

  async ensureBucket() {
    if (!this.remote) return;
    const r = await fetch(this.objectUrl(), { headers:this.headers() });
    if (!r.ok) throw new Error(`Persistent storage init failed (${r.status})${await errorDetail(r)}`);
  }

  async putObject(objectPath, bytes, contentType='application/octet-stream') {
    if (!this.remote) {
      write(this.localPath(objectPath),bytes);
      return;
    }
    const r = await fetch(this.objectUrl(objectPath), {
      method:'PUT',
      headers:this.headers({'Content-Type':contentType}),
      body:bytes,
    });
    if (!r.ok) throw new Error(`Persistent upload failed (${r.status})${await errorDetail(r)}`);
  }

  async getObject(objectPath) {
    if (!this.remote) {
      const p=this.localPath(objectPath);
      return fs.existsSync(p)?read(p):null;
    }
    const r = await fetch(this.objectUrl(objectPath), { headers:this.headers() });
    if (r.status===404) return null;
    if (!r.ok) throw new Error(`Persistent download failed (${r.status})${await errorDetail(r)}`);
    return Buffer.from(await r.arrayBuffer());
  }

  async deleteObject(objectPath) {
    if (!this.remote) {
      const p=this.localPath(objectPath);
      if(fs.existsSync(p)) fs.unlinkSync(p);
      return;
    }
    const r=await fetch(this.objectUrl(objectPath), {
      method:'DELETE',
      headers:this.headers(),
    });
    if(!r.ok) throw new Error(`Persistent delete failed (${r.status})${await errorDetail(r)}`);
  }

  async restoreDatabase(dbPath) {
    if (!this.remote) return false;
    const bytes=await this.getObject('_system/bookreader.db');
    if(!bytes) return false;
    write(dbPath,bytes);
    return true;
  }
}
