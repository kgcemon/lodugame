// PM2, on the VPS: pm2 delete ludo && pm2 start ecosystem.config.cjs && pm2 save
// One process: matches live in its memory, so a second worker would split them.
module.exports = {
  apps: [
    {
      name: 'ludo',
      // Node itself, not `npm start`: no idle npm process, and SIGTERM reaches the server. Not as a
      // script with an interpreter either: PM2's loader would become the main module and the server
      // would never start listening.
      script: 'node',
      interpreter: 'none',
      args: '--import tsx --max-old-space-size=512 server/index.ts',
      exec_mode: 'fork',
      instances: 1,
      // Time to finish the match writes under way (see the SIGTERM handler).
      kill_timeout: 8000,
      // A crash loop backs off instead of hammering the database with recovery work.
      exp_backoff_restart_delay: 500,
      max_restarts: 30,
      min_uptime: 10000,
      max_memory_restart: '800M',
      time: true,
      merge_logs: true,
    },
  ],
};
