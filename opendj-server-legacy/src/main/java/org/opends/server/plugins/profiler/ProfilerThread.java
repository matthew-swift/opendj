/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2008 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.plugins.profiler;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.opends.server.api.DirectoryThread;
import org.forgerock.opendj.io.*;
import org.forgerock.i18n.slf4j.LocalizedLogger;

import static org.opends.server.util.StaticUtils.*;

/**
 * This class defines a thread that may be used to actually perform
 * profiling in the Directory Server.  When activated, it will repeatedly
 * retrieve thread stack traces and store them so that they can be written out
 * and analyzed with a separate utility.
 */
public class ProfilerThread
       extends DirectoryThread
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();




  /** Indicates whether a request has been received to stop profiling. */
  private boolean stopProfiling;

  /** The time at which the capture started. */
  private long captureStartTime;

  /** The time at which the capture stopped. */
  private long captureStopTime;

  /** The number of intervals for which we have captured data. */
  private long numIntervals;

  /** The sampling interval that will be used by this thread. */
  private long sampleInterval;

  /** The set of thread stack traces captured by this profiler thread. */
  private HashMap<ProfileStack,Long> stackTraces;

  /** The thread that is actually performing the capture. */
  private Thread captureThread;



  /**
   * Creates a new profiler thread that will obtain stack traces at the
   * specified interval.
   *
   * @param  sampleInterval  The length of time in milliseconds between polls
   *                         for stack trace information.
   */
  public ProfilerThread(long sampleInterval)
  {
    super("Directory Server Profiler Thread");


    this.sampleInterval = sampleInterval;

    stackTraces      = new HashMap<>();
    numIntervals     = 0;
    stopProfiling    = false;
    captureStartTime = -1;
    captureStopTime  = -1;
    captureThread    = null;
  }



  /**
   * Runs in a loop, periodically capturing a list of the stack traces for all
   * active threads.
   */
  @Override
  public void run()
  {
    captureThread    = currentThread();
    captureStartTime = System.currentTimeMillis();

    while (! stopProfiling)
    {
      // Get the current time so we can sleep more accurately.
      long startTime = System.currentTimeMillis();


      // Get a stack trace of all threads that are currently active.
      Map<Thread,StackTraceElement[]> stacks = getAllStackTraces();
      numIntervals++;


      // Iterate through the threads and process their associated stack traces.
      for (Thread t : stacks.keySet())
      {
        // We don't want to capture information about the profiler thread.
        if (t == currentThread())
        {
          continue;
        }


        // We'll skip over any stack that doesn't have any information.
        StackTraceElement[] threadStack = stacks.get(t);
        if (threadStack == null || threadStack.length == 0)
        {
          continue;
        }


        // Create a profile stack for this thread stack trace and get its
        // current count.  Then put the incremented count.
        ProfileStack profileStack = new ProfileStack(threadStack);
        Long currentCount = stackTraces.get(profileStack);
        if (currentCount == null)
        {
          // This is a new trace that we haven't seen, so its count will be 1.
          stackTraces.put(profileStack, 1L);
        }
        else
        {
          // This is a repeated stack, so increment its count.
          stackTraces.put(profileStack, 1L+currentCount.intValue());
        }
      }


      // Determine how long we should sleep and do so.
      if (! stopProfiling)
      {
        long sleepTime =
             sampleInterval - (System.currentTimeMillis() - startTime);
        if (sleepTime > 0)
        {
          try
          {
            Thread.sleep(sleepTime);
          }
          catch (Exception e)
          {
            logger.traceException(e);
          }
        }
      }
    }

    captureStopTime = System.currentTimeMillis();
    captureThread   = null;
  }



  /**
   * Causes the profiler thread to stop capturing stack traces.  This method
   * will not return until the thread has stopped.
   */
  public void stopProfiling()
  {
    stopProfiling  = true;

    try
    {
      if (captureThread != null)
      {
        captureThread.join();
      }
    }
    catch (Exception e)
    {
      logger.traceException(e);
    }
  }



  /**
   * Writes the information captured by this profiler thread to the specified
   * file.  This should only be called after
   *
   * @param  filename  The path and name of the file to write.
   *
   * @throws  IOException  If a problem occurs while trying to write the
   *                       capture data.
   */
  public void writeCaptureData(String filename)
         throws IOException
  {
    // Open the capture file for writing.  We'll use an ASN.1 writer to write
    // the data.
    FileOutputStream fos = new FileOutputStream(filename);
    ASN1Writer writer = ASN1.getWriter(fos);


    try
    {
      if (captureStartTime < 0)
      {
        captureStartTime = System.currentTimeMillis();
        captureStopTime  = captureStartTime;
      }
      else if (captureStopTime < 0)
      {
        captureStopTime = System.currentTimeMillis();
      }


      // Write a header to the file containing the number of samples and the
      // start and stop times.
      writer.writeStartSequence();
      writer.writeInteger(numIntervals);
      writer.writeInteger(captureStartTime);
      writer.writeInteger(captureStopTime);
      writer.writeEndSequence();


      // For each unique stack captured, write it to the file followed by the
      // number of occurrences.
      for (ProfileStack s : stackTraces.keySet())
      {
        s.write(writer);
        writer.writeInteger(stackTraces.get(s));
      }
    }
    finally
    {
      // Make sure to close the file when we're done.
      close(writer, fos);
    }
  }
}

