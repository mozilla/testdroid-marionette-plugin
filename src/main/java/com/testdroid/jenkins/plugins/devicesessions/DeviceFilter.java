package com.testdroid.jenkins.plugins.devicesessions;

import org.kohsuke.stapler.DataBoundConstructor;

public class DeviceFilter {

    public final String label;
    public final String value;

    @DataBoundConstructor
    public DeviceFilter(String label, String value) {
        this.label = label;
        this.value = value;
    }

    @Override
    public String toString() {
        return "[DeviceFilter: label=" + label + ", value=" + value + "]";
    }
}