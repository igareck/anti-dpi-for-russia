# Host checks

Run these commands from the project root with Python 3 and a JDK.
Use --help for each runner's options.

- test_proxy_lifecycle.py exercises the production proxy, scan and
  process lifecycle against Android API fixtures. It exports the catalogue
  command log used by the native web probe runner.
- test_youtube.py covers the production site probes and scan verdicts.
  Run it after test_proxy_lifecycle.py.
- test_web_native.py checks the probes through a host build of nfqws2,
  with controlled HTTPS fixtures. Build the host engine with
  tools/build_nfqws2.py --host.
- test_usb_activation.py covers the USB commands, authorized provider access,
  one-time release of the script, authentication, time limit and cancellation.
- test_activation_service.py and test_adb_runtime.py check pairing, port
  discovery and foreground activation with Android framework fixtures.
- test_worker.py, test_worker_bridge.py and test_restoration.py cover
  authenticated local RPC, reattachment, cleanup, boot and restoration.
- test_dashboard_state.cjs covers the dashboard's interaction state.

These checks use fixtures for the Android radio and USB stack.
