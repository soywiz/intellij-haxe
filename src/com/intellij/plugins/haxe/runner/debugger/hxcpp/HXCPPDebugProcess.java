package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.idea.LoggerFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Pair;
import com.intellij.plugins.haxe.runner.debugger.HaxeDebuggerEditorsProvider;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.connection.HXCPPConnection;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.connection.HXCPPResponse;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.frame.HXCPPStackFrame;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.frame.HXCPPSuspendContext;
import com.intellij.util.io.socketConnection.AbstractResponseHandler;
import com.intellij.util.io.socketConnection.AbstractResponseToRequestHandler;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * @author: Fedor.Korotkov
 */
public class HXCPPDebugProcess extends XDebugProcess {
  private static final Logger LOG = LoggerFactory.getInstance().getLoggerInstance(HXCPPDebugProcess.class.getName());
  private final ExecutionResult myExecutionResult;
  private final HXCPPConnection myConnection = new HXCPPConnection();
  private final HXCPPBreakpointsHandler myBreakpointsHandler;
  private final Module myModule;

  private final LinkedList<Pair<String[], AbstractResponseToRequestHandler<HXCPPResponse>>> commandsToWrite =
    new LinkedList<Pair<String[], AbstractResponseToRequestHandler<HXCPPResponse>>>() {
      @Override
      public synchronized Pair<String[], AbstractResponseToRequestHandler<HXCPPResponse>> removeFirst() {
        waitForData();
        return super.removeFirst();
      }

      @Override
      public synchronized void addFirst(final Pair<String[], AbstractResponseToRequestHandler<HXCPPResponse>> debuggerCommand) {
        super.addFirst(debuggerCommand);
        notify();
      }

      @Override
      public synchronized void addLast(final Pair<String[], AbstractResponseToRequestHandler<HXCPPResponse>> debuggerCommand) {
        super.addLast(debuggerCommand);
        notify();
      }

      private void waitForData() {
        try {
          while (size() == 0) {
            wait();
          }
        }
        catch (InterruptedException ex) {
          throw new RuntimeException(ex);
        }
      }
    };

  @Override
  protected ProcessHandler doGetProcessHandler() {
    return myExecutionResult != null ? myExecutionResult.getProcessHandler() : null;
  }

  @NotNull
  @Override
  public ExecutionConsole createConsole() {
    if (myExecutionResult != null) {
      return myExecutionResult.getExecutionConsole();
    }
    return super.createConsole();
  }

  public HXCPPDebugProcess(@NotNull XDebugSession session, Module module, int debuggingPort, ExecutionResult executionResult)
    throws IOException {
    super(session);

    myBreakpointsHandler = new HXCPPBreakpointsHandler(this);
    myExecutionResult = executionResult;
    myModule = module;
    startCommandProcessingThread(debuggingPort);
  }

  public Module getModule() {
    return myModule;
  }

  private void startCommandProcessingThread(final int debuggingPort) {
    ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
      public void run() {
        myConnection.connect(debuggingPort);
        myConnection.registerHandler(new AbstractResponseHandler<HXCPPResponse>() {
          @Override
          public void processResponse(HXCPPResponse response) {
            HXCPPDebugProcess.this.processResponse(response);
          }
        });

        boolean connected = false;

        try {
          myConnection.open();
          connected = true;
        }
        catch (IOException ignored) {
          LOG.debug(ignored);
        }

        while (true) {
          processOneCommandLoop();
        }
      }
    });
  }

  private void processOneCommandLoop() {
    final Pair<String[], AbstractResponseToRequestHandler<HXCPPResponse>> command = commandsToWrite.removeFirst();
    myConnection.sendCommand(command.getSecond(), command.getFirst());
  }

  public void processResponse(HXCPPResponse response) {
    LOG.debug("processResponse:" + response.toString());

    final String command = response.getResponseString();
    if (command != null && command.contains("stopped.")) {
      sendCommand(new AbstractResponseToRequestHandler<HXCPPResponse>() {
        @Override
        public boolean processResponse(HXCPPResponse response) {
          if (!response.getResponseString().contains("Must break first.")) {
            tryParseAndBreak(response);
          }
          else {
            // known bug. try one more time
            sendCommand(new AbstractResponseToRequestHandler<HXCPPResponse>() {
              @Override
              public boolean processResponse(HXCPPResponse response) {
                tryParseAndBreak(response);
                return true;
              }
            }, "where");
          }
          return true;
        }
      }, "where");
    }
  }

  private void tryParseAndBreak(HXCPPResponse response) {
    final List<HXCPPStackFrame> stackFrames = HXCPPStackFrame.parse(this, response.getResponseString());
    if (!stackFrames.isEmpty()) {
      getSession().positionReached(new HXCPPSuspendContext(this, stackFrames));
    }
  }

  @NotNull
  @Override
  public XDebuggerEditorsProvider getEditorsProvider() {
    return new HaxeDebuggerEditorsProvider();
  }

  public XBreakpointHandler<?>[] getBreakpointHandlers() {
    return myBreakpointsHandler.getBreakpointHandlers();
  }

  @Override
  public void startStepOver() {
  }

  @Override
  public void startStepInto() {
  }

  @Override
  public void startStepOut() {
  }

  public void sendCommand(@Nullable final AbstractResponseToRequestHandler<HXCPPResponse> requestHandler, String... args) {
    commandsToWrite.addLast(Pair.create(args, requestHandler));
  }

  @Override
  public void stop() {
    myConnection.close();
  }

  @Override
  public void resume() {
    sendCommand(null, "c");
  }

  @Override
  public void startPausing() {
  }

  @Override
  public void runToPosition(@NotNull XSourcePosition position) {
  }
}