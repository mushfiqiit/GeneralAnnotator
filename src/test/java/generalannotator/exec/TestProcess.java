package generalannotator.exec;

import java.nio.file.Path;

/** A tiny, portable child process for runner tests, launched with the current JVM. */
public final class TestProcess {

    private TestProcess() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> {
                System.out.print("out:" + args[1]);
                System.err.print("err:" + args[1]);
            }
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "env" -> System.out.print(System.getenv(args[1]));
            case "cwd" -> System.out.print(Path.of("").toAbsolutePath());
            case "flood" -> {
                // Far more than a pipe buffer on both streams, interleaved.
                String line = "x".repeat(1023) + "\n";
                for (int i = 0; i < 2048; i++) {
                    System.out.print(line);
                    System.err.print(line);
                }
            }
            case "sleep" -> {
                System.out.println("started");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    /** Command list that runs this class with the current JVM and test classpath. */
    public static java.util.List<String> command(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TestProcess.class.getName());
        command.addAll(java.util.List.of(args));
        return command;
    }
}
