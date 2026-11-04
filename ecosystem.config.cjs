// PM2, on the VPS: pm2 delete ludo && pm2 start ecosystem.config.cjs && pm2 save
// One process: matches live in its memory, so a second worker would split them.
module.exports = {
  apps: [
    {
      name: 'ludo',
      // Node directly, not `npm start`: no idle npm process, and SIGTERM reaches the server.
      script: 'server/index.ts',
      interpreter: 'node',
      interpreter_args: '--import tsx --max-old-space-size=512',
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
