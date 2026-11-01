// Runs the Socket.IO game server and the Vite dev server together.
import { spawn } from 'node:child_process';

const isWin = process.platform === 'win32';
const procs = [
  ['server', ['run', 'dev:server']],
  ['client', ['run', 'dev:client']],
].map(([name, args]) => {
  const p = spawn(isWin ? 'npm.cmd' : 'npm', args, { stdio: 'inherit', shell: isWin });
  p.on('exit', (code) => {
    console.log(`[${name}] exited with ${code}`);
    procs.forEach((q) => q !== p && q.kill());
    process.exit(code ?? 0);
  });
  return p;
});

process.on('SIGINT', () => procs.forEach((p) => p.kill()));
