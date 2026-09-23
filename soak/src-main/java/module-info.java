/// Opt-in JFR soak harness for sava's RPC clients. Not published, not part of `check`, PIT or
/// fuzzing: it drives the library's own HTTP and websocket clients — the code that parses what
/// untrusted nodes send — over sustained workloads against controlled local peers, under Flight
/// Recorder, to find lifecycle, recovery and retention defects before a bad peer does.
module software.sava.rpc.soak {
  // the subject
  requires software.sava.rpc;
  // the #52 consumer: ravina's websocket recovery manager, exercised around sava
  requires software.sava.ravina_solana;

  // harness-only JFR events and in-process RecordingStream counters
  requires jdk.jfr;
  // controlled local JSON-RPC peer (com.sun.net.httpserver)
  requires jdk.httpserver;
  // the client transport under test, and the harness's own probes
  requires java.net.http;
  // MXBean heap/GC/thread gauges sampled alongside the recording
  requires java.management;
  // com.sun.management: the Unix fd count and the GC notification payloads the gauge reads
  requires jdk.management;
  // System.Logger output from sava and ravina arrives through the JUL backend
  requires java.logging;
}
