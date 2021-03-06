package com.google.sitebricks.mail;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.sitebricks.util.BoundedDiscardingList;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.softee.management.annotation.MBean;
import org.softee.management.annotation.ManagedAttribute;
import org.softee.management.annotation.ManagedOperation;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command/response handler for a single mail connection/user.
 *
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 */
@MBean
class MailClientHandler extends SimpleChannelHandler {
  private static final Logger log = LoggerFactory.getLogger(MailClientHandler.class);

  private static boolean ENABLE_WIRE_TRACE = true;

  private static final Map<String, Boolean> logAllMessagesForUsers = new ConcurrentHashMap<String, Boolean>();

  public static final String CAPABILITY_PREFIX = "* CAPABILITY";
  static final Pattern AUTH_SUCCESS_REGEX =
      Pattern.compile("[.] OK .*@.* \\(Success\\)", Pattern.CASE_INSENSITIVE);

  static final Pattern COMMAND_FAILED_REGEX =
      Pattern.compile("^[.] (NO|BAD) (.*)", Pattern.CASE_INSENSITIVE);
  static final Pattern MESSAGE_COULDNT_BE_FETCHED_REGEX =
      Pattern.compile("^\\d+ no some messages could not be fetched \\(failure\\)\\s*",
          Pattern.CASE_INSENSITIVE);
  static final Pattern SYSTEM_ERROR_REGEX = Pattern.compile("[*]\\s*bye\\s*system\\s*error\\s*",
      Pattern.CASE_INSENSITIVE);

  static final Pattern IDLE_ENDED_REGEX = Pattern.compile(".* OK IDLE terminated \\(success\\)\\s*",
      Pattern.CASE_INSENSITIVE);
  static final Pattern IDLE_EXISTS_REGEX = Pattern.compile("\\* (\\d+) exists\\s*",
      Pattern.CASE_INSENSITIVE);
  static final Pattern IDLE_EXPUNGE_REGEX = Pattern.compile("\\* (\\d+) expunge\\s*",
      Pattern.CASE_INSENSITIVE);

  private final Idler idler;
  private final MailClientConfig config;

  private final CountDownLatch loginSuccess = new CountDownLatch(1);
  private volatile List<String> capabilities;
  private volatile FolderObserver observer;
  final AtomicBoolean idleRequested = new AtomicBoolean();
  final AtomicBoolean idleAcknowledged = new AtomicBoolean();
  private final Object idleMutex = new Object();

  // Panic button.
  private volatile boolean halt = false;

  private final LinkedBlockingDeque<Error> errorStack = new LinkedBlockingDeque<Error>();
  private final Queue<CommandCompletion> completions =
      new ConcurrentLinkedQueue<CommandCompletion>();
  private volatile PushedData pushedData;

  private final BoundedDiscardingList<String> commandTrace = new BoundedDiscardingList<String>(10);
  private final BoundedDiscardingList<String> wireTrace = new BoundedDiscardingList<String>(25);
  private final InputBuffer inputBuffer = new InputBuffer();


  public MailClientHandler(Idler idler, MailClientConfig config) {
    this.idler = idler;
    this.config = config;
  }

  // For debugging, use with caution!
  public static void addUserForVerboseLogging(String username, boolean toStdOut) {
    logAllMessagesForUsers.put(username, toStdOut);
  }

  @ManagedOperation
  public void setReceiveLogging(boolean b) {
    log.info("setReceiveLogging[" + config.getUsername() + "] = " + b);
    if (b)
      logAllMessagesForUsers.put(config.getUsername(), false);
    else
      logAllMessagesForUsers.remove(config.getUsername());
  }

  @ManagedAttribute
  public Set<String> getLogAllMessagesFor() {
    return logAllMessagesForUsers.keySet();
  }

  @ManagedAttribute
  public List<String> getCommandTrace() {
    return commandTrace.list();
  }

  public List<String> getWireTrace() {
    return wireTrace.list();
  }

  @ManagedAttribute
  public boolean isLoggedIn() {
    return loginSuccess.getCount() == 0;
  }

  private static class PushedData {
    volatile boolean idleExitSent = false;
    // guarded by idleMutex.
  	final ArrayList<Integer> pushAdds = new ArrayList<Integer>();
    // guarded by idleMutex.
  	final ArrayList<Integer> pushRemoves = new ArrayList<Integer>();
  }

  // DO NOT synchronize!
  public void enqueue(CommandCompletion completion) {
    completions.add(completion);
    commandTrace.add(new Date().toString() + " " + completion.toString());
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    String message = e.getMessage().toString();
    for (String input : inputBuffer.processMessage(message)) {
      processMessage(input);
    }
  }

  private void processMessage(String message) throws Exception {
    Boolean toStdOut = logAllMessagesForUsers.get(config.getUsername());
    if (toStdOut != null) {
      if (toStdOut)
        System.out.println("IMAPrcv[" + config.getUsername() + "]: " + message);
      else
        log.info("IMAPrcv[{}]: {}", config.getUsername(), message);
    }

    if (ENABLE_WIRE_TRACE) {
      wireTrace.add(message);
      log.trace(message);
    }

    if (SYSTEM_ERROR_REGEX.matcher(message).matches()
        || ". NO [ALERT] Account exceeded command or bandwidth limits. (Failure)".equalsIgnoreCase(
        message.trim())) {
      log.warn("{} disconnected by IMAP Server due to system error: {}", config.getUsername(),
          message);
      disconnectAbnormally(message);
      return;
    }

    try {
      if (halt) {
        log.error("Mail client for {} is halted but continues to receive messages, ignoring!",
            config.getUsername());
        return;
      }
      if (loginSuccess.getCount() > 0) {
        if (message.startsWith(CAPABILITY_PREFIX)) {
          this.capabilities = Arrays.asList(
              message.substring(CAPABILITY_PREFIX.length() + 1).split("[ ]+"));
          return;
        } else if (AUTH_SUCCESS_REGEX.matcher(message).matches()) {
          log.info("Authentication success for user {}", config.getUsername());
          loginSuccess.countDown();
        } else {
          Matcher matcher = COMMAND_FAILED_REGEX.matcher(message);
          if (matcher.find()) {
            // WARNING: DO NOT COUNTDOWN THE LOGIN LATCH ON FAILURE!!!

            log.warn("Authentication failed for {} due to: {}", config.getUsername(), message);
            errorStack.push(new Error(null /* logins have no completion */, extractError(matcher),
                wireTrace.list()));
            disconnectAbnormally(message);
          }
        }
        return;
      }

      // Copy to local var as the value can change underneath us.
      FolderObserver observer = this.observer;
      if (idleRequested.get() || idleAcknowledged.get()) {
        synchronized (idleMutex) {
          if (IDLE_ENDED_REGEX.matcher(message).matches()) {
            idleRequested.compareAndSet(true, false);
            idleAcknowledged.set(false);

            // Now fire the events.
            PushedData data = pushedData;
            pushedData = null;

            idler.idleEnd();
            observer.changed(data.pushAdds.isEmpty() ? null : data.pushAdds,
                data.pushRemoves.isEmpty() ? null : data.pushRemoves);
            return;
          }

          // Queue up any push notifications to publish to the client in a second.
          Matcher existsMatcher = IDLE_EXISTS_REGEX.matcher(message);
          boolean matched = false;
          if (existsMatcher.matches()) {
            int number = Integer.parseInt(existsMatcher.group(1));
            pushedData.pushAdds.add(number);
            matched = true;
          } else {
            Matcher expungeMatcher = IDLE_EXPUNGE_REGEX.matcher(message);
            if (expungeMatcher.matches()) {
              int number = Integer.parseInt(expungeMatcher.group(1));
              pushedData.pushRemoves.add(number);
              matched = true;
            }
          }

          // Stop idling, when we get the idle ended message (next cycle) we can publish what's been gathered.
          if (matched) {
            if(!pushedData.idleExitSent) {
              idler.done();
              pushedData.idleExitSent = true;
            }
            return;
          }
        }
      }

      complete(message);
    } catch (Exception ex) {
      CommandCompletion completion = completions.poll();
      if (completion != null)
        completion.error(message, ex);
      else {
        log.error("Strange exception during mail processing (no completions available!): {}",
            message, ex);
        errorStack.push(new Error(null, "No completions available!", wireTrace.list()));
      }
      throw ex;
    }
  }

  private void disconnectAbnormally(String message) {
    try {
      halt();
      // Disconnect abnormally. The user code should reconnect using the mail client.
      errorStack.push(new Error(completions.poll(), message, wireTrace.list()));

      idler.disconnectAsync();
    } finally {
      disconnected();
    }
  }

  private String extractError(Matcher matcher) {
    return (matcher.groupCount()) > 1 ? matcher.group(2) : matcher.group();
  }

  /**
   * This is synchronized to ensure that we process the queue serially.
   */
  private synchronized void complete(String message) {
    // This is a weird problem with writing stuff while idling. Need to investigate it more, but
    // for now just ignore it.
    if (MESSAGE_COULDNT_BE_FETCHED_REGEX.matcher(message).matches()) {
      log.warn("Some messages in the batch could not be fetched for {}\n" +
          "---cmd---\n{}\n---wire---\n{}\n---end---\n", new Object[] {
          config.getUsername(),
          getCommandTrace(),
          getWireTrace()
      });
      errorStack.push(new Error(completions.peek(), message, wireTrace.list()));
      final CommandCompletion completion = completions.peek();
      String errorMsg = "Some messages in the batch could not be fetched for user " + config.getUsername();
      RuntimeException ex = new RuntimeException(errorMsg);
      if (completion != null) {
        completion.error(errorMsg, new MailHandlingException(getWireTrace(), errorMsg, ex));
        completions.poll();
      } else {
        throw ex;
      }
    }

    CommandCompletion completion = completions.peek();
    if (completion == null) {
      if ("+ idling".equalsIgnoreCase(message)) {
        synchronized (idleMutex) {
          idler.idleStart();
          log.trace("IDLE entered.");
          idleAcknowledged.set(true);
        }
      } else {
        log.error("Could not find the completion for message {} (Was it ever issued?)", message);
        errorStack.push(new Error(null, "No completion found!", wireTrace.list()));
      }
      return;
    }

    if (completion.complete(message)) {
      completions.poll();
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
    log.error("Exception caught! Disconnecting...", e.getCause());
    disconnectAbnormally(e.getCause().getMessage());
  }

  public List<String> getCapabilities() {
    return capabilities;
  }

  boolean awaitLogin() {
    try {
      if (!loginSuccess.await(10L, TimeUnit.SECONDS)) {
        disconnectAbnormally("Timed out waiting for login response");
        throw new RuntimeException("Timed out waiting for login response");
      }

      return isLoggedIn();
    } catch (InterruptedException e) {
      errorStack.push(new Error(null, e.getMessage(), wireTrace.list()));
      throw new RuntimeException("Interruption while awaiting server login", e);
    }
  }

  MailClient.WireError lastError() {
    return errorStack.peek() != null ? errorStack.pop() : null;
  }

  List<String> getLastTrace() {
    return wireTrace.list();
  }

  /**
   * Registers a FolderObserver to receive events happening with a particular
   * folder. Typically an IMAP IDLE feature. If called multiple times, will
   * overwrite the currently set observer.
   */
  void observe(FolderObserver observer) {
    synchronized (idleMutex) {
      this.observer = observer;
      pushedData = new PushedData();
      idleAcknowledged.set(false);
    }
  }

  void halt() {
    halt = true;
  }

  public boolean isHalted() {
    return halt;
  }

  public void disconnected() {
  }

  static class Error implements MailClient.WireError {
    final CommandCompletion completion;
    final String error;
    final List<String> wireTrace;

    Error(CommandCompletion completion, String error, List<String> wireTrace) {
      this.completion = completion;
      this.error = error;
      this.wireTrace = wireTrace;
    }

    @Override
    public String message() {
      return error;
    }

    @Override
    public List<String> trace() {
      return wireTrace;
    }

    @Override
    public String expected() {
      return completion == null ? null : completion.toString();
    }

    @Override
    public String toString() {
      StringBuilder sout = new StringBuilder();
      sout.append("WireError: ");
      sout.append("Completion=").append(completion);
      sout.append(", Error: ").append(error);
      sout.append(", Trace:\n");
      for (String s : wireTrace) {
        sout.append("  ").append(s).append("\n");
      }
      return sout.toString();
    }
  }

  @VisibleForTesting
  static class InputBuffer {
    volatile private StringBuilder buffer = new StringBuilder();

    @VisibleForTesting
    List<String> processMessage(String message) {
      // Nuke all CR characters, and we'll only count LF.
      message = message.replaceAll("\r", "");
      // Split leaves a trailing empty line if there's a terminating newline.
      ArrayList<String> split = Lists.newArrayList(message.split("\n", -1));
      Preconditions.checkArgument(split.size() > 0);

      synchronized (this) {
        buffer.append(split.get(0));
        if (split.size() == 1) // no newlines.
          return ImmutableList.of();
        split.set(0, buffer.toString());
        buffer = new StringBuilder().append(split.remove(split.size() - 1));
      }
      return split;
    }
  }
}
