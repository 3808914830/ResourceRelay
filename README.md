# ResourceRelay
Driver Manager · A Purely Local Root Module Toolbox

What it is

A purely local Android Root module management tool that helps you flash, enable, disable, and delete modules. No network access, no data collection.

What it does

· Flash modules: Supports .zip (Magisk / KernelSU / APatch modules) and .sh scripts. Automatically detects your current Root solution and calls the corresponding install command.
· Manage modules: Lists system drivers, user drivers, and custom drivers. Supports enable, disable, and delete.
· View environment: Displays kernel version, SELinux status, System partition read/write state, Bootloader lock state, and Zygisk detection.
· Kernel parameters: Browse runtime parameters under /proc/sys. Supports viewing, editing, and hex read/write.

What it does NOT do

· Does not provide Root access. Your device must already be rooted and authorized.
· Does not implement module mounting. Module activation depends on Magisk / KernelSU / APatch itself.
· Does not bypass any Root detection, and does not hide Root.
· No network access, no data collection, no uploads.

Compatibility

· Root solutions: Magisk / KernelSU / APatch.
· Devices with readable kernel version: detected automatically.
· Some Huawei / Honor devices: kernel version cannot be read directly due to system restrictions; it is estimated based on device model code and is for reference only.
· HarmonyOS 5.0 and above: system restrictions may limit some features.

Risk Notice

Flashing modules, modifying kernel parameters, and deleting system drivers all carry risks and may cause the device to fail to boot. Please make sure you understand your device and have backups before proceeding. You are solely responsible for any consequences of using this tool.

Features

· Pure native implementation, no third-party libraries, small size, fast startup.
· No network permission, purely local.
· Transparent code, free to decompile and inspect.
· Version 0.19, under continuous development.
