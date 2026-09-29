# Windows background options

Decision: PinkCollab runs the Gateway as a same-user Windows Service. It starts
before login, survives logout, and matches the macOS/Linux user-service model.

A per-user Task Scheduler logon task was considered as an alternative because it
can avoid storing an account password and administrator elevation. It is not
implemented: it requires an interactive desktop session, so headless operation
after reboot or logout cannot be promised, and it needs lifecycle tests
(shutdown of OMP descendants, lock/unlock, expired passwords, duplicate
instances, interrupted upgrade recovery) before adoption. Silent migration from
a service would change host availability and needs its own contract.
