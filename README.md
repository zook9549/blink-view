# blink-view
Blink View takes the snapshots recorded via your Blink cameras and adds them to a Google Photo Album.  This enriches the experience by having longer retention, easier sharing, favoriting, and commenting across the videos - all via the built-in features of Google Photos.

# How It Works
Blink View will trigger on a scheduled basis (defaults to hourly) to find new captures for all registered cameras.  For any new captures, these are then uploaded to a Google Photo album that matches the camera name.
# Setup
The setup is a bit tedious on this since Google requires OAuth for Photos API and not a service account.  The good news is that you can grant access through a dev environment (localhost) and then copy the data file to any other environment.
## Google OAuth Setup
Follow the directions at https://developers.google.com/photos/library/guides/get-started-java
## Blink Setup
In application.properties, enter in your Blink email and Blink password into the corresponding properties.  These can be abstracted out into secured property files using Spring configuration and environment standards.
# Additional Property Configuration
Set your output directories in application.properties.  These specify where to find your cred files and where to cache snapshots/results.
# Running the solution 
Fire it up via standard Spring Boot.  While this does setup a Tomcat instance to run as well, it's largely unnecessary.  The only REST service is to trigger to the process via /getVideos.

All results are stored in results.json under the snapshot directory.  Clean out this director if you want to do an entirely fresh pull of videos.

# Side Notes
The Blink API is not supported.  Use at your own risk, but Google Photos is much better than the mobile application provided.
I have not figured out how to correctly set the created date for uploaded videos via the API. It will use the upload date, even though the file metadata is correct.
In addition to authenticating with OAuth through localhost, I also recommend doing your first auth with Blink via dev.  You will get prompted to enter a PIN that gets emails to you the first time, which you need to do via the verify PIN API through your favorite web service tool.

Special thanks to MattTW for putting in the work to document the Blink API at https://github.com/MattTW/BlinkMonitorProtocol.  Without that, I would not have even attempted to pull this together.

# See it in action
The inspiration was a quarantine focused project with my son to build a squirrel house out of an old shelf, along with a built-in camera.  The Blink was the best fit I could find with IR and being completely wireless.  The snapshots are uploaded hourly to https://photos.google.com/share/AF1QipOIgOPKaESpNXicPKU3gzls8Cui25DAjnKtYSYHYCH1i1xGMuLXS9MzHFm74gHZCw?key=djJCaDZpa3NvMUpweWNNLVJOdGFHUlkxODJsVFF3.

