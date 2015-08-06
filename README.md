# Testdroid Marionette Plugin  [![Build Status][travis-image]][travis-url]
This [Jenkins][jenkins] plugin provisions a Firefox OS device via Testdroid.

## Source code
The source code for this plugin is available [here][source].

## How to build
Use `mvn clean package` to build the plugin for deployment.

## How to install
Navigate to the 'Advanced' tab in the Jenkins Plugin Manager, and use the
'Upload Plugin' section to select the `testdroid-marionette.hpi` file created
using the build instructions above. If the plugin was already present then a
restart of Jenkins may be required.

![Upload plugin][upload_plugin]

## Global Configuration
The plugin must first be configured in the system configuration. Here you can
provide the Testdroid API endpoint, your username, and your password:

![Global configuration][global_config]

You can also provide advanced options such as the maximum session duration, how
long to wait for a device to be flashed, how many attempts to flash a device
before failing the build, and to skip flashing if a device is found with the
target build:

![Advanced global configuration][global_config_advanced]

## Job Configuration
To get a job to provision a device, check the 'Testdroid device session'
checkbox under 'Build Environment' in the job configuration page. Here you will
specify the URL to the build you want to flash, the amount of memory to
allocate on the device, and any device filters:

![Job configuration][job_config]

### Build URL
This is required, and should point to the zip file containing the build you
want to flash.

### Throttle memory
Leave the default of 0 if you don't want to throttle memory, otherwise this is
the amount of memory (in MB) that you want to allocate on the device.

### Device filters
These allow you to filter devices based on the labels associated with them in
the Testdroid console. For example, if you have a device group named 'SIMs'
with values indicating the number of active SIM cards in the device, you may
configure your filter as shown below:

![Device filter configuration][device_filter_config]

### Flash project
An advanced option that allows you to specify and alternative flash project.
This should match a project that exists in the Testdroid console.

## How it works
When activated, the plugin will authenticate with Testdroid at the start of
each build and request a device session. If an available device matches the
filters and build it will be selected and a session will be started. If there
are devices that match the filter but not the build, then the URL will be used
to flash one of the matched devices. After this, the plugin will search again
for a match and start a session. If no devices are matched then the plugin will
retry according to the global configuration, before eventually failing the
build.

Once a session is created, several environment variables will be injected into
the build. These allow your build steps to communicate with the device:

* **ANDROID_SERIAL** - Device serial identifier for use with ADB.
* **ADB_HOST** - Host that the ADB server is running on.
* **ADB_PORT** - Port that the ADB server is running on.
* **DEVICE_DATA** - Path to device data file.
* **MARIONETTE_HOST** - Host that the Marionette server is running on.
* **MARIONETTE_PORT** - Port that the Marionette server is running on.
* **SESSION_ID** - Testdroid session identifier.

### Device data
The device data file is a JSON file containing the label groups and values as
specified for the selected device in the Testdroid console. The **DEVICE_DATA**
environment variable contains the path to this file.

### Android debug bridge (ADB)
ADB is a command line tool that allows you to run commands on an attached
device. Each session will provide the **ADB_HOST** and **ADB_PORT** environment
variables, which you can use for the `-H` and `-P` arguments to connect ADB to
a remote server. The device's serial is stored in **ANDROID_SERIAL**, which you
can use for the `-s` argument to target the specific device. More information
on ADB can be found [here][adb].

### Marionette
Marionette is a automation tool for Firefox, which allows you to remotely
control a Firefox instance. Each session provides **MARIONETTE_HOST** and
**MARIONETTE_PORT**, which can be used to connect to the remote instance and
start a Marionette session. More information on Marionette can be found
[here][marionette].

### Troubleshooting
The console logs and Jenkins logs can be useful when investigating issues with
the plugin. If Testdroid is experiencing issues when attempting to flash
devices then additional log files will be written to the workspace. These will
have the name `flash-[id].log` where `[id]` is the unique project run
identifier. You can also use the Testdroid web console to assist with
investigating failures.

[jenkins]: http://jenkins-ci.org/  "Jenkins"
[source]: https://github.com/mozilla/testdroid-marionette-plugin  "Source code"
[adb]: https://developer.android.com/tools/help/adb.html "Android Debug Bridge"
[marionette]: https://developer.mozilla.org/en-US/docs/Mozilla/QA/Marionette "Marionette"

[upload_plugin]: https://raw.githubusercontent.com/mozilla/testdroid-marionette-plugin/master/images/upload_plugin.png "Upload plugin"
[global_config]: https://raw.githubusercontent.com/mozilla/testdroid-marionette-plugin/master/images/global_config.png "Global configuration"
[global_config_advanced]: https://raw.githubusercontent.com/mozilla/testdroid-marionette-plugin/master/images/global_config_advanced.png "Advanced global configuration"
[job_config]: https://raw.githubusercontent.com/mozilla/testdroid-marionette-plugin/master/images/job_config.png "Job configuration"
[device_filter_config]: https://raw.githubusercontent.com/mozilla/testdroid-marionette-plugin/master/images/device_filter_config.png "Device filter configuration"
[travis-image]: https://travis-ci.org/mozilla/testdroid-marionette-plugin.svg?branch=master
[travis-url]: https://travis-ci.org/mozilla/testdroid-marionette-plugin
