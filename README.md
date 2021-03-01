The lookup table for the supported hubs is placed here:
  app/src/main/java/com/gromaudio/powerbalancing/HubManager.java

To support a new Hub, just add it's VID:PID into the mLookupTable array.
Also, you can add the new Hub's VID:PID to "app/src/main/res/xml/device_filter.xml" if you want the app to start automatically when the Hub is connected.
