# The capture helper is started by name through app_process, not referenced from code.
-keep class cloud.samlo.couch.capture.Capture { public static void main(java.lang.String[]); }
