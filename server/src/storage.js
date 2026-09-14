import fs from 'node:fs';
import path from 'node:path';

const read = p => fs.readFileSync(p);
const write = (p,b) => { fs.mkdirSync(path.dirname(p),{recursive:true}); fs.writeFileSync(p,b); };

export class PersistentStore {
  constructor({ root, supabaseUrl, serviceRoleKey, bucket = 'bookreader' }) {
    this.root = root;
    this.supabaseUrl = (supabaseUrl || '').replace(/\/$/,'');
    this.key = serviceRoleKey || '';
    this.bucket = bucket;
    this.remote = Boolean(this.supabaseUrl && this.key);
  }

  headers(extra={}) {
    return { Authorization:`Bearer ${this.key}`, apikey:this.key, ...extra };
  }

  async ensureBucket() {
    if (!this.remote) return;
    const r = await fetch(`${this.supabaseUrl}/storage/v1/bucket/${encodeURIComponent(this.bucket)}`, { headers:this.headers() });
    if (r.ok) return;
    const c = await fetch(`${this.supabaseUrl}/storage/v1/bucket`, {
      method:'POST', headers:this.headers({'Content-Type':'application/json'}),
      body:JSON.stringify({ id:this.bucket, name:this.bucket, public:false, file_size_limit:209715200 }),
    });
    if (!c.ok && c.status !== 409) throw new Error(`Storage bucket init failed (${c.status})`);
  }

  async putObject(objectPath, bytes, contentType='application/octet-stream') {
    if (!this.remote) {
      write(path.join(this.root,'objects',objectPath),bytes);
      return;
    }
    const r = await fetch(`${this.supabaseUrl}/storage/v1/object/${encodeURIComponent(this.bucket)}/${objectPath.split('/').map(encodeURIComponent).join('/')}`, {
      method:'POST', headers:this.headers({'Content-Type':contentType,'x-upsert':'true'}), body:bytes,
    });
    if (!r.ok) throw new Error(`Persistent upload failed (${r.status})`);
  }

  async getObject(objectPath) {
    if (!this.remote) {
      const p=path.join(this.root,'objects',objectPath); return fs.existsSync(p)?read(p):null;
    }
    const r = await fetch(`${this.supabaseUrl}/storage/v1/object/authenticated/${encodeURIComponent(this.bucket)}/${objectPath.split('/').map(encodeURIComponent).join('/')}`, { headers:this.headers() });
    if (r.status===404) return null;
    if (!r.ok) throw new Error(`Persistent download failed (${r.status})`);
    return Buffer.from(await r.arrayBuffer());
  }

  async deleteObject(objectPath) {
    if (!this.remote) {
      const p=path.join(this.root,'objects',objectPath); if(fs.existsSync(p)) fs.unlinkSync(p); return;
    }
    const r=await fetch(`${this.supabaseUrl}/storage/v1/object/${encodeURIComponent(this.bucket)}`, {
      method:'DELETE', headers:this.headers({'Content-Type':'application/json'}), body:JSON.stringify({ prefixes:[objectPath] }),
    });
    if(!r.ok) throw new Error(`Persistent delete failed (${r.status})`);
  }

  async restoreDatabase(dbPath) {
    if (!this.remote || fs.existsSync(dbPath)) return false;
    const bytes=await this.getObject('_system/bookreader.db');
    if(!bytes) return false;
    write(dbPath,bytes); return true;
  }

  async backupDatabase(dbPath) {
    if (!this.remote || !fs.existsSync(dbPath)) return;
    await this.putObject('_system/bookreader.db',read(dbPath),'application/x-sqlite3');
  }
}
