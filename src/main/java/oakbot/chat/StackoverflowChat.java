package oakbot.chat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.Consts;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.JsonNode;

import oakbot.chat.RobustClient.JsonResponse;

/**
 * A connection to Stackoverflow chat. This class is thread-safe (fingers
 * crossed).
 * @author Michael Angstadt
 * @see <a href="http://chat.stackoverflow.com">chat.stackoverflow.com</a>
 * @see <a href=
 * "https://github.com/Zirak/SO-ChatBot/blob/master/source/adapter.js">Good
 * explanation of how SO Chat works</a>
 */
public class StackoverflowChat implements ChatConnection {
	private static final Logger logger = Logger.getLogger(StackoverflowChat.class.getName());
	private static final String DOMAIN = "stackoverflow.com";
	private static final String CHAT_DOMAIN = "https://chat." + DOMAIN;
	private static final int MAX_MESSAGE_LENGTH = 500;
	private static final Pattern fkeyRegex = Pattern.compile("value=\"([0-9a-f]{32})\"");
	private static final Duration EDIT_TIME_LIMIT = Duration.ofMinutes(2);

	private final CloseableHttpClient client;

	/**
	 * Stores the "fkey" value for each room.
	 */
	public final Map<Integer, String> fkeyCache = new HashMap<>();

	/**
	 * The messages that were retrieved the last time a room was pinged.
	 */
	private final Map<Integer, List<ChatMessage>> lastMessageBatch = new HashMap<>();

	/**
	 * The ID of the latest message that was retrieved from each chat room.
	 */
	private final Map<Integer, Long> lastMessageProcessed = new HashMap<>();

	private final long retryPause, heartbeat;
	private boolean closed;

	/**
	 * Creates a polling connection to Stackoverflow chat.
	 * @param client the HTTP client
	 */
	public StackoverflowChat(CloseableHttpClient client) {
		this(client, 5000, 3000);
	}

	/**
	 * Creates a polling connection to Stackoverflow chat.
	 * @param client the HTTP client
	 * @param retryPause the base amount of time to wait in between retries when
	 * a request fails due to network glitches (in milliseconds)
	 * @param heartbeat how often to poll each chat room looking for new
	 * messages (in milliseconds)
	 */
	public StackoverflowChat(CloseableHttpClient client, long retryPause, long heartbeat) {
		this.client = client;
		this.retryPause = retryPause;
		this.heartbeat = heartbeat;
	}

	@Override
	public void login(String email, String password) throws InvalidCredentialsException, IOException {
		logger.info("Logging in as " + email + "...");

		String fkey = parseFkeyFromUrl("https://" + DOMAIN + "/users/login");
		if (fkey == null) {
			throw new IOException("\"fkey\" field not found on login page, cannot login.");
		}

		LoginRequest request = new LoginRequest(email, password, fkey);
		try (CloseableHttpResponse response = client.execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode != 302) {
				throw new InvalidCredentialsException();
			}
		}
	}

	@Override
	public void joinRoom(int roomId) throws RoomNotFoundException, RoomPermissionException, IOException {
		synchronized (this) {
			if (lastMessageProcessed.containsKey(roomId)) {
				//already joined
				return;
			}
		}

		//"prime the pump"
		List<ChatMessage> messages = getNextMessageBatch(roomId, -1);
		long messageId = messages.isEmpty() ? -1 : messages.get(0).getMessageId();

		synchronized (this) {
			lastMessageProcessed.put(roomId, messageId);
			lastMessageBatch.put(roomId, messages);
		}
	}

	@Override
	public void leaveRoom(int roomId) {
		String fkey;
		synchronized (this) {
			/*
			 * If the room has no entry in the prevMessages map, then it means
			 * the room was never joined.
			 */
			if (!lastMessageProcessed.containsKey(roomId)) {
				return;
			}

			/*
			 * If it has a prevMessages entry, then it should always have an
			 * fkey, but check to be sure.
			 */
			fkey = fkeyCache.get(roomId);
			if (fkey == null) {
				return;
			}
		}

		/*
		 * Send a leave request to the room. It's not crucial that this request
		 * succeed because it doesn't really do much. All it does is make the
		 * bot's user portrait disappear from the room list (however, it only
		 * disappears if you refresh the web browser). You'd think it would play
		 * that "fall & fade away" animation that you see when someone leaves,
		 * but it doesn't.
		 */
		LeaveRoomRequest request = new LeaveRoomRequest(roomId, fkey);
		try {
			send(request).attempts(1).asHttp();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Problem leaving room " + roomId + ".", e);
		}

		/*
		 * The fkey does not need to be removed from the fkey cache--fkeys stay
		 * the same during the entire login session.
		 */
		//fkeyCache.remove(roomId);

		synchronized (this) {
			lastMessageProcessed.remove(roomId);
			lastMessageBatch.remove(roomId);
		}
	}

	@Override
	public long sendMessage(int roomId, String message) throws IOException {
		return sendMessage(roomId, message, SplitStrategy.NONE).get(0);
	}

	@Override
	public List<Long> sendMessage(int roomId, String message, SplitStrategy splitStrategy) throws IOException {
		String fkey = getFKey(roomId);

		List<String> parts;
		if (message.contains("\n")) {
			//messages with newlines have no length limit
			parts = Arrays.asList(message);
		} else {
			parts = splitStrategy.split(message, MAX_MESSAGE_LENGTH);
		}

		List<Long> messageIds = new ArrayList<>(parts.size());
		for (String part : parts) {
			NewMessageRequest request = new NewMessageRequest(roomId, part, fkey);
			JsonResponse jsonResponse = send(request).statusCodes(200).asJson();
			if (jsonResponse.isHttp404()) {
				/*
				 * If we got this far, it means the room has an fkey, which
				 * means the room exists. So if a 404 response is returned when
				 * trying to send a message, it more likely means that the bot's
				 * permission to post messages has been revoked.
				 * 
				 * If a 404 response is returned from this request, the response
				 * body reads:
				 * "The room does not exist, or you do not have permission"
				 */
				throw new RoomNotFoundException(roomId);
			}

			NewMessageResponse response = NewMessageResponse.parse(jsonResponse);
			messageIds.add(response.getId());
		}

		return messageIds;
	}

	@Override
	public List<ChatMessage> getMessages(int roomId, int count) throws IOException {
		String fkey = getFKey(roomId);

		GetMessagesRequest request = new GetMessagesRequest(roomId, count, fkey);
		JsonResponse jsonResponse = send(request).attempts(5).statusCodes(200).asJson();
		if (jsonResponse.isHttp404()) {
			throw new RoomNotFoundException(roomId);
		}
		GetMessagesResponse response = GetMessagesResponse.parse(jsonResponse);

		return response.getMessages();
	}

	@Override
	public boolean deleteMessage(int roomId, long messageId) throws IOException {
		String fkey = getFKey(roomId);

		DeleteMessageRequest request = new DeleteMessageRequest(messageId, fkey);
		DeleteMessageResponse response;
		try (CloseableHttpResponse httpResponse = send(request).statusCodes(200, 302).asHttp()) {
			response = DeleteMessageResponse.parse(httpResponse);
		}

		return response.isSuccess();
	}

	@Override
	public boolean editMessage(int roomId, long messageId, String updatedMessage) throws IOException {
		String fkey = getFKey(roomId);

		EditMessageRequest request = new EditMessageRequest(messageId, fkey, updatedMessage);
		EditMessageResponse response;
		try (CloseableHttpResponse httpResponse = send(request).statusCodes(200).asHttp()) {
			response = EditMessageResponse.parse(httpResponse);
		}

		return response.isSuccess();
	}

	@Override
	public UserInfo getUserInfo(int userId, int roomId) throws IOException {
		GetUserInfoRequest request = new GetUserInfoRequest(userId, roomId);
		JsonResponse jsonResponse = send(request).statusCodes(200).asJson();
		if (jsonResponse.isHttp404()) {
			return null;
		}

		GetUserInfoResponse response = GetUserInfoResponse.parse(jsonResponse, roomId);
		return (response == null) ? null : response.getUserInfo();
	}

	@Override
	public void listen(ChatMessageHandler handler) throws IOException {
		while (true) {
			long start = System.currentTimeMillis();

			/*
			 * Make a copy of the room list to prevent concurrent modification
			 * exceptions.
			 */
			List<Integer> rooms;
			synchronized (this) {
				rooms = new ArrayList<>(lastMessageProcessed.keySet());
			}

			for (Integer room : rooms) {
				logger.fine("Pinging room " + room + ".");

				Long prevMessageId;
				List<ChatMessage> prevMessages;
				synchronized (this) {
					prevMessageId = lastMessageProcessed.get(room);
					prevMessages = lastMessageBatch.get(room);
				}

				if (prevMessageId == null) {
					//another thread left the room
					continue;
				}

				List<ChatMessage> messages = getNextMessageBatch(room, prevMessageId);
				if (!messages.isEmpty()) {
					List<ChatMessage> newMessages = new ArrayList<>();
					for (int i = messages.size() - 1; i >= 0; i--) {
						ChatMessage message = messages.get(i);

						long id = message.getMessageId();
						if (id <= prevMessageId) {
							break;
						}

						newMessages.add(message);
					}

					List<ChatMessage> editedMessages = new ArrayList<>();
					for (ChatMessage message : messages) {
						long id = message.getMessageId();
						String content = message.getContent();
						for (ChatMessage prevMessage : prevMessages) {
							if (prevMessage.getMessageId() == id) {
								if (!prevMessage.getContent().equals(content)) {
									editedMessages.add(message);
								}
								break;
							}
						}
					}

					if (!newMessages.isEmpty() || !editedMessages.isEmpty()) {
						logger.fine(newMessages.size() + " new messages and " + editedMessages.size() + " edited messages found.");

						for (ChatMessage message : newMessages) {
							handler.onMessage(message);
						}
						for (ChatMessage message : editedMessages) {
							handler.onMessageEdited(message);
						}
					}
				}

				synchronized (this) {
					if (!messages.isEmpty()) {
						ChatMessage last = messages.get(messages.size() - 1);
						lastMessageProcessed.put(room, last.getMessageId());
					}
					lastMessageBatch.put(room, messages);
				}

				if (closed) {
					return;
				}
			}

			//sleep before pinging again
			long elapsed = System.currentTimeMillis() - start;
			long sleep = heartbeat - elapsed;
			if (sleep > 0) {
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/**
	 * Gets the next batch of messages to process from a chat room. At the very
	 * least, the last two minutes worth of messages will be returned. If there
	 * are older messages which haven't been retrieved yet, those will be
	 * returned as well.
	 * @param room the room ID
	 * @param prevMessageId the ID of the latest message that was previously
	 * retrieved or -1 to simply retrieve the last two minutes worth of messages
	 * @return the messages (in chronological order)
	 * @throws IOException if there's a network problem
	 */
	private List<ChatMessage> getNextMessageBatch(int room, long prevMessageId) throws IOException {
		/*
		 * Keep retrieving more and more messages until we get all new messages,
		 * as well as all messages that were posted in the last 2 minutes.
		 */
		LocalDateTime twoMinuteMark = LocalDateTime.now().minus(EDIT_TIME_LIMIT);
		List<ChatMessage> messages;
		boolean timeBoundaryReached = false;
		boolean idBoundaryReached = (prevMessageId == -1);
		int count = 10;
		while (true) {
			messages = getMessages(room, count);
			if (messages.size() < count) {
				/*
				 * There are no more messages in the chat room.
				 */
				break;
			}

			ChatMessage first = messages.get(0);

			LocalDateTime timestamp = first.getTimestamp();
			if (timestamp.isBefore(twoMinuteMark)) {
				timeBoundaryReached = true;
			}

			long messageId = first.getMessageId();
			if (messageId <= prevMessageId) {
				idBoundaryReached = true;
			}

			if (timeBoundaryReached && idBoundaryReached) {
				break;
			}

			count *= 2;
		}

		/*
		 * Trim off the messages that are older than 2 minutes, unless they
		 * haven't been retrieved yet.
		 */
		int start = -1;
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage message = messages.get(i);

			LocalDateTime timestamp = message.getTimestamp();
			if (timestamp.isBefore(twoMinuteMark)) {
				long messageId = message.getMessageId();
				if (prevMessageId != -1 && messageId > prevMessageId) {
					start = i;
					break;
				}
			} else {
				start = i;
				break;
			}
		}

		if (start < 0) {
			return Collections.emptyList();
		}

		return messages.subList(start, messages.size());
	}

	/**
	 * Parses the "fkey" parameter from a webpage.
	 * @param url the URL of the webpage
	 * @return the fkey or null if not found
	 * @throws IOException if there's a problem loading the page
	 */
	private String parseFkeyFromUrl(String url) throws IOException {
		HttpGet request = new HttpGet(url);
		String html;
		try (CloseableHttpResponse response = send(request).asHttp()) {
			html = EntityUtils.toString(response.getEntity());
		}
		return parseFkey(html);
	}

	/**
	 * Parses the "fkey" parameter from an HTML page.
	 * @param html the HTML page
	 * @return the fkey or null if not found
	 */
	private static String parseFkey(String html) {
		Matcher m = fkeyRegex.matcher(html);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * Gets the "fkey" parameter for a room.
	 * @param roomId the room ID
	 * @return the fkey
	 * @throws RoomNotFoundException if the room does not exist
	 * @throws RoomPermissionException if messages cannot be posted to this room
	 * @throws IOException if there's a network problem
	 */
	private synchronized String getFKey(int roomId) throws IOException {
		String fkey = fkeyCache.get(roomId);
		if (fkey != null) {
			return fkey;
		}

		ChatRoomLobbyRequest request = new ChatRoomLobbyRequest(roomId);
		ChatRoomLobbyResponse response;
		try (CloseableHttpResponse httpResponse = send(request).statusCodes(200).asHttp()) {
			response = ChatRoomLobbyResponse.parse(httpResponse);
		}

		if (response.isNonExistant()) {
			throw new RoomNotFoundException(roomId);
		}

		fkey = response.getFkey();
		if (fkey == null) {
			throw new IOException("Could not get fkey of room " + roomId + ".");
		}

		if (!response.isAllowedToPost()) {
			throw new RoomPermissionException(roomId);
		}

		fkeyCache.put(roomId, fkey);
		return fkey;
	}

	@Override
	public void close() throws IOException {
		flush();

		List<Integer> watchedRooms;
		synchronized (this) {
			watchedRooms = new ArrayList<>(lastMessageProcessed.keySet());
		}

		for (Integer roomId : watchedRooms) {
			leaveRoom(roomId);
		}

		try {
			client.close();
		} finally {
			closed = true;
		}
	}

	@Override
	public void flush() {
		//empty
	}

	private static class LoginRequest extends HttpPost {
		public LoginRequest(String email, String password, String fkey) {
			super("https://" + DOMAIN + "/users/login");

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("email", email),
				new BasicNameValuePair("password", password),
				new BasicNameValuePair("fkey", fkey)
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class ChatRoomLobbyRequest extends HttpGet {
		public ChatRoomLobbyRequest(int roomId) {
			super(CHAT_DOMAIN + "/rooms/" + roomId);
		}
	}

	private static class ChatRoomLobbyResponse {
		private final String fkey;
		private final boolean nonExistant, canPostToRoom;

		public ChatRoomLobbyResponse(boolean nonExistant, String fkey, boolean canPostToRoom) {
			this.nonExistant = nonExistant;
			this.fkey = fkey;
			this.canPostToRoom = canPostToRoom;
		}

		public static ChatRoomLobbyResponse parse(HttpResponse response) throws IOException {
			/*
			 * A 404 response is returned if the room doesn't exist.
			 * 
			 * A 404 also seems to be returned if the room is inactive, but the
			 * bot does not have enough reputation/privileges to see inactive
			 * rooms. If I view the same room in a web browser under my personal
			 * account (which has over 20k rep and is a room owner), I can see
			 * the room. And when I make the bot login under my own account and
			 * then view an inactive room, the bot does not get a 404.
			 */
			boolean nonExistant = response.getStatusLine().getStatusCode() == 404;

			String html = EntityUtils.toString(response.getEntity());

			String fkey = parseFkey(html);

			/*
			 * The textbox for sending messages won't be there if the bot can't
			 * post to the room.
			 */
			boolean canPostToRoom = html.contains("<textarea id=\"input\">");

			return new ChatRoomLobbyResponse(nonExistant, fkey, canPostToRoom);
		}

		public String getFkey() {
			return fkey;
		}

		public boolean isAllowedToPost() {
			return canPostToRoom;
		}

		public boolean isNonExistant() {
			return nonExistant;
		}
	}

	private static class NewMessageRequest extends HttpPost {
		public NewMessageRequest(int roomId, String message, String fkey) {
			super(CHAT_DOMAIN + "/chats/" + roomId + "/messages/new");

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("text", message),
				new BasicNameValuePair("fkey", fkey)
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class NewMessageResponse {
		private final long id;
		private final LocalDateTime time;

		public NewMessageResponse(long id, LocalDateTime time) {
			this.id = id;
			this.time = time;
		}

		//{"id":36436674,"time":1491157087}
		public static NewMessageResponse parse(JsonResponse response) {
			JsonNode body = response.getBody();
			long id = body.get("id").asLong();
			LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(body.get("time").asLong()), ZoneId.systemDefault());

			return new NewMessageResponse(id, time);
		}

		public long getId() {
			return id;
		}

		@SuppressWarnings("unused")
		public LocalDateTime getTime() {
			return time;
		}
	}

	private static class LeaveRoomRequest extends HttpPost {
		public LeaveRoomRequest(int roomId, String fkey) {
			super(CHAT_DOMAIN + "/chats/leave/" + roomId);

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("quiet", "true"), //setting this parameter to "false" results in an error
				new BasicNameValuePair("fkey", fkey)
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class GetMessagesRequest extends HttpPost {
		public GetMessagesRequest(int roomId, int count, String fkey) {
			super(CHAT_DOMAIN + "/chats/" + roomId + "/events");

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("mode", "messages"),
				new BasicNameValuePair("msgCount", Integer.toString(count)),
				new BasicNameValuePair("fkey", fkey)
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class GetMessagesResponse {
		private final List<ChatMessage> messages;

		public GetMessagesResponse(List<ChatMessage> messages) {
			this.messages = messages;
		}

		public static GetMessagesResponse parse(JsonResponse response) {
			JsonNode body = response.getBody();
			JsonNode events = body.get("events");

			List<ChatMessage> messages;
			if (events == null) {
				messages = new ArrayList<>(0);
			} else {
				messages = new ArrayList<>();
				Iterator<JsonNode> it = events.elements();
				while (it.hasNext()) {
					JsonNode element = it.next();
					ChatMessage chatMessage = parseChatMessage(element);
					messages.add(chatMessage);
				}
			}

			return new GetMessagesResponse(messages);
		}

		public List<ChatMessage> getMessages() {
			return messages;
		}

		/**
		 * Unmarshals a chat message from its JSON representation.
		 * @param element the JSON element
		 * @return the parsed chat message
		 */
		private static ChatMessage parseChatMessage(JsonNode element) {
			ChatMessage.Builder builder = new ChatMessage.Builder();

			JsonNode value = element.get("content");
			if (value != null) {
				//TODO convert HTML to SO Chat markdown?
				String content = StringEscapeUtils.unescapeHtml4(value.asText());
				builder.content(content);
			}

			/*
			 * This field is always zero, no matter how many edits a message
			 * gets.
			 */
			//value = element.get("edits");

			value = element.get("message_id");
			if (value != null) {
				builder.messageId(value.asLong());
			}

			value = element.get("room_id");
			if (value != null) {
				builder.roomId(value.asInt());
			}

			value = element.get("time_stamp");
			if (value != null) {
				LocalDateTime ts = timestamp(value.asLong());
				builder.timestamp(ts);
			}

			value = element.get("user_id");
			if (value != null) {
				builder.userId(value.asInt());
			}

			value = element.get("user_name");
			if (value != null) {
				builder.username(value.asText());
			}

			return builder.build();
		}
	}

	private static class EditMessageRequest extends HttpPost {
		public EditMessageRequest(long messageId, String fkey, String updatedMessage) {
			super(CHAT_DOMAIN + "/messages/" + messageId);

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("text", updatedMessage),
				new BasicNameValuePair("fkey", fkey)
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class EditMessageResponse {
		private final boolean success, alreadyDeleted, tooLate, notYourMessage;

		public EditMessageResponse(boolean success, boolean alreadyDeleted, boolean tooLate, boolean notYourMessage) {
			this.success = success;
			this.alreadyDeleted = alreadyDeleted;
			this.tooLate = tooLate;
			this.notYourMessage = notYourMessage;
		}

		public static EditMessageResponse parse(HttpResponse response) throws IOException {
			String message = EntityUtils.toString(response.getEntity());
			boolean success = message.equals("\"ok\"");
			boolean alreadyDeleted = message.equals("\"This message has already been deleted and cannot be edited\"");
			boolean tooLate = message.equals("\"It is too late to edit this message\"");
			boolean notYourMessage = message.equals("\"You can only edit your own messages\"");

			return new EditMessageResponse(success, alreadyDeleted, tooLate, notYourMessage);
		}

		public boolean isSuccess() {
			return success;
		}

		@SuppressWarnings("unused")
		public boolean isAlreadyDeleted() {
			return alreadyDeleted;
		}

		@SuppressWarnings("unused")
		public boolean isTooLate() {
			return tooLate;
		}

		@SuppressWarnings("unused")
		public boolean isNotYourMessage() {
			return notYourMessage;
		}
	}

	private static class DeleteMessageRequest extends HttpPost {
		public DeleteMessageRequest(long messageId, String fkey) {
			super(CHAT_DOMAIN + "/messages/" + messageId + "/delete");

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("fkey", fkey)
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class DeleteMessageResponse {
		private final boolean success, alreadyDeleted, tooLate, notYourMessage, nonExistantMessage;

		public DeleteMessageResponse(boolean success, boolean alreadyDeleted, boolean tooLate, boolean notYourMessage, boolean nonExistantMessage) {
			this.success = success;
			this.alreadyDeleted = alreadyDeleted;
			this.tooLate = tooLate;
			this.notYourMessage = notYourMessage;
			this.nonExistantMessage = nonExistantMessage;
		}

		public static DeleteMessageResponse parse(HttpResponse response) throws IOException {
			/*
			 * 302 response is returned if the message ID does not reference a
			 * message that has ever existed before.
			 */
			boolean nonExistantMessage = (response.getStatusLine().getStatusCode() == 302);

			String message = EntityUtils.toString(response.getEntity());
			boolean success = message.equals("\"ok\"");
			boolean alreadyDeleted = message.equals("\"This message has already been deleted.\"");
			boolean tooLate = message.equals("\"It is too late to delete this message\"");
			boolean notYourMessage = message.equals("\"You can only delete your own messages\"");

			return new DeleteMessageResponse(success, alreadyDeleted, tooLate, notYourMessage, nonExistantMessage);
		}

		public boolean isSuccess() {
			return success;
		}

		@SuppressWarnings("unused")
		public boolean isAlreadyDeleted() {
			return alreadyDeleted;
		}

		@SuppressWarnings("unused")
		public boolean isTooLate() {
			return tooLate;
		}

		@SuppressWarnings("unused")
		public boolean isNotYourMessage() {
			return notYourMessage;
		}

		@SuppressWarnings("unused")
		public boolean isNonExistantMessage() {
			return nonExistantMessage;
		}
	}

	private static class GetUserInfoRequest extends HttpPost {
		public GetUserInfoRequest(int userId, int roomId) {
			super(CHAT_DOMAIN + "/user/info");

			//@formatter:off
			List<NameValuePair> params = Arrays.asList(
				new BasicNameValuePair("ids", Integer.toString(userId)),
				new BasicNameValuePair("roomId", Integer.toString(roomId))
			);
			//@formatter:on
			setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		}
	}

	private static class GetUserInfoResponse {
		private final UserInfo userInfo;

		public GetUserInfoResponse(UserInfo userInfo) {
			this.userInfo = userInfo;
		}

		public UserInfo getUserInfo() {
			return userInfo;
		}

		public static GetUserInfoResponse parse(JsonResponse response, int roomId) throws IOException {
			JsonNode userNode = response.getBody().get("users").get(0);
			if (userNode == null) {
				return null;
			}

			UserInfo.Builder builder = new UserInfo.Builder();
			builder.userId(userNode.get("id").asInt());
			builder.roomId(roomId);
			builder.username(userNode.get("name").asText());

			String profilePicture;
			String emailHash = userNode.get("email_hash").asText();
			if (emailHash.startsWith("!")) {
				profilePicture = emailHash.substring(1);
			} else {
				profilePicture = "https://www.gravatar.com/avatar/" + emailHash + "?d=identicon&s=128";
			}
			builder.profilePicture(profilePicture);

			builder.reputation(userNode.get("reputation").asInt());
			builder.moderator(userNode.get("is_moderator").asBoolean());
			builder.owner(userNode.get("is_owner").asBoolean());
			builder.lastPost(timestamp(userNode.get("last_post").asLong()));
			builder.lastSeen(timestamp(userNode.get("last_seen").asLong()));

			return new GetUserInfoResponse(builder.build());
		}
	}

	private static LocalDateTime timestamp(long ts) {
		Instant instant = Instant.ofEpochSecond(ts);
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}

	private RobustClient send(HttpUriRequest request) {
		return new RobustClient(client, request).retryPause(retryPause);
	}
}
