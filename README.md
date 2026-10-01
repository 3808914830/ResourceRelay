Driver Manager

---

What It Is

A front-end tool for flashing modules on rooted Android devices.

It sits on top of Magisk / KernelSU / APatch. It does not provide root. It does not manage root. Root comes from the mask.

It only does one thing: push modules in, fast.

---

What It Replaces

It replaces the flashing UI of the mask.

Instead of opening Magisk, going into modules, picking a file, confirming twice — you open this, pick a file, tap once, done.

---

What's Inside (roughly)

· A flashing page — you pick a file, it runs the right command for your root type, shows live log.
· A manager page — lists installed modules, delete with one tap.
· A kernel config page — adjust kernel parameters.
· A guard module — checks environment, backs up before flash, verifies after.
· A root check module — detects whether you're on Magisk, KernelSU, or APatch.
· A shell helper — runs commands with root, handles timeout and kill.

That's basically it. A few pages, a few background helpers.

---

Supported Files

.zip / .sh / .ko

Auto-detects root type and calls the right backend.

Root Type Command Used
Magisk magisk --install-module
KernelSU ksud module install
APatch magisk --install-module (compat)

---

Safety Layer

Not a guardrail. A safety rope.

Before flash — environment check, auto backup, backup verify.
During flash — 30s silence watchdog, live log, instant abort.
After flash — module verify, SELinux context fix, report saved.

---

What It Does NOT Do

· Does not manage root
· Does not grant root
· Does not check file format
· Does not inspect script contents
· Does not verify kernel compatibility
· Does not verify file signature

Root comes from the mask. This only takes the wheel at the flashing step.

---

Compatibility

Root Provider Supported
Magisk Yes
KernelSU Yes
APatch Yes

Auto-detected. No config needed.

---

One Line

The mask provides root. This provides the fastest way to push a module in.
