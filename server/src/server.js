import express from 'express';
import multer from 'multer';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { v4 as uuid } from 'uuid';
import fs from 'node:fs';
import path from 'node:path';

const app = express();
const port = Number(process.env.PORT || 8787);
const root = path.resolve(process.env.DATA_DIR || './data');
const secret = process.env.JWT_SECRET || 'development-only-change-me';
const tmpDir = path.join(root, 'tmp');
fs.mkdirSync(tmpDir, { recursive: true });
const db = new Database(path.join(root, 'bookreader.db'));
db.exec(`CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,title TEXT NOT NULL,file_name TEXT NOT NULL,stored_name TEXT NOT NULL,last_page INTEGER NOT NULL DEFAULT 0,uploaded_at TEXT NOT NULL);`);
app.use(express.json());
app.use(express.static('public'));
const upload = multer({ dest: tmpDir, limits: { fileSize: 200 * 1024 * 1024 } });

function tokenFor(user) { return jwt.sign({ sub: user.id, email: user.email }, secret, { expiresIn: '90d' }); }
function auth(req,res,next) { const raw=req.headers.authorization||''; if(!raw.startsWith('Bearer ')) return res.status(401).json({error:'Unauthorized'}); try { req.user=jwt.verify(raw.slice(7),secret); next(); } catch { res.status(401).json({error:'Invalid token'}); } }

app.post('/api/register',(req,res)=>{ try { const email=String(req.body.email||'').trim().toLowerCase(); const password=String(req.body.password||''); if(!email||password.length<6) return res.status(400).json({error:'Email and password (6+ chars) are required'}); const user={id:uuid(),email}; db.prepare('INSERT INTO users(id,email,password_hash) VALUES(?,?,?)').run(user.id,email,bcrypt.hashSync(password,12)); res.json({token:tokenFor(user)}); } catch(e){ res.status(409).json({error:'Account already exists'}); } });
app.post('/api/login',(req,res)=>{ const email=String(req.body.email||'').trim().toLowerCase(); const user=db.prepare('SELECT * FROM users WHERE email=?').get(email); if(!user||!bcrypt.compareSync(String(req.body.password||''),user.password_hash)) return res.status(401).json({error:'Invalid email or password'}); res.json({token:tokenFor(user)}); });
app.get('/api/books',auth,(req,res)=>{ const rows=db.prepare('SELECT id,title,file_name AS fileName,last_page AS lastPage,uploaded_at AS uploadedAt FROM books WHERE user_id=? ORDER BY uploaded_at DESC').all(req.user.sub); res.json(rows); });
app.post('/api/books',auth,upload.single('pdf'),(req,res)=>{ if(!req.file) return res.status(400).json({error:'PDF is required'}); const name=req.file.originalname||'document.pdf'; if(!name.toLowerCase().endsWith('.pdf')) { fs.unlinkSync(req.file.path); return res.status(400).json({error:'PDF only'}); } const id=uuid(); const userDir=path.join(root,'books',req.user.sub); fs.mkdirSync(userDir,{recursive:true}); const stored=id+'.pdf'; fs.renameSync(req.file.path,path.join(userDir,stored)); const title=String(req.body.title||name.replace(/\.pdf$/i,'')); const uploadedAt=new Date().toISOString(); db.prepare('INSERT INTO books(id,user_id,title,file_name,stored_name,last_page,uploaded_at) VALUES(?,?,?,?,?,0,?)').run(id,req.user.sub,title,name,stored,uploadedAt); res.json({id,title,fileName:name,lastPage:0,uploadedAt}); });
app.get('/api/books/:id/file',auth,(req,res)=>{ const b=db.prepare('SELECT * FROM books WHERE id=? AND user_id=?').get(req.params.id,req.user.sub); if(!b) return res.sendStatus(404); res.type('application/pdf').sendFile(path.join(root,'books',req.user.sub,b.stored_name)); });
app.put('/api/books/:id/progress',auth,(req,res)=>{ const page=Math.max(0,Number(req.body.page)||0); const r=db.prepare('UPDATE books SET last_page=? WHERE id=? AND user_id=?').run(page,req.params.id,req.user.sub); if(!r.changes) return res.sendStatus(404); res.json({ok:true,page}); });
app.get('/health',(_,res)=>res.json({ok:true}));
app.listen(port,'0.0.0.0',()=>console.log(`BookReader server listening on ${port}`));
