package jenkins.plugins.testdroid;

import hudson.model.TaskListener;

import java.io.Serializable;

public class TestdroidLogger implements Serializable {

    private TaskListener listener;

    public TestdroidLogger(TaskListener listener) {
        this.listener = listener;
    }

    public TaskListener getListener() {
        return listener;
    }

    public void info(String message) {
        listener.getLogger().println("[Testdroid] - " + message);
    }

    public void error(String message) {
        listener.getLogger().println("[Testdroid] - [ERROR] - " + message);
    }

    public void warn(String message) { listener.getLogger().println("[Testdroid] - [WARN] - " + message); }
}
