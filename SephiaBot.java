/* SephiaBot
 * Copyright (C) 2005 Jorge Rodriguez and Ed Anderson
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA
 */

import java.util.*;
import java.io.IOException;
import java.net.UnknownHostException;

class SephiaBot implements IRCConnectionListener {

	private SephiaBotData data;
	
	private IRCConnection connections[];

	private long nextWho;
	private long nextHi;
	private final long SPAM_WAIT = 1000; // ms
	private final long CHECK_ANNOUNCE_WAIT = 1000; // ms
	private long lastCheckAnnounce;
	private boolean greet = true;

	private long lastNickAttempt = 0;

	//XXX: For every place censor() is used, IRCConnection must set currChannel higher in the stack for it to work correctly.
	private boolean censor(IRCConnection con) { return data.getCensor(con.getIndex(), con.getCurrentChannel()); }

	public static void main(String args[]) {
		String cfgPath = "sephiabot.xml";
		boolean greet = true;
		final String usage = "\nUsage: sephiabot [-c config file] [--nogreet]\n" +
			" Default config file: ./sephiabot.xml";

		if (args != null && args.length > 0) {
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("--help")) {
					System.out.println(usage);
					System.exit(0);
				} else if (args[i].equals("-c")) {
					if (args.length > i+1 && !args[i+1].startsWith("-"))
						cfgPath = args[i+1];
					else {
						System.out.println("You must specify the path of your config file with -c" + usage);
						System.exit(0);
					}
					i++; //Skip the next argument
				} else if (args[i].equals("--nogreet")) {
					greet = false;
				} else {
					System.out.println("Invalid argument: " + args[i] + usage);
					System.exit(0);
				}
			}
		}

		SephiaBot sephiaBot = new SephiaBot(cfgPath, greet);
		sephiaBot.connect();

		while (sephiaBot.hasConnections()) {
			sephiaBot.poll();
		}
		sephiaBot.log("All connections have been closed. Exiting.");
	}

	public SephiaBot(String config, boolean greet) {

		this.nextWho = 0;
		this.nextHi = 0;
		this.greet = greet;

		this.data = new SephiaBotData(config);
		
		log("----------------------------------------------------------------------------\nSephiaBot Started!");
		
		data.parseConfig();

		// this must happen after config parsing.
		this.connections = new IRCConnection[data.getNumNetworks()];
	}

	boolean hasConnections() {
		for (int i = 0; i < connections.length; i++)
			if (connections[i].isConnected())
				return true;
		return false;
	}
	
	String makeTime(long time) {
		long dur = Math.abs(time - System.currentTimeMillis()); 
		String result = "";
		dur /= 1000L;//Seconds
		if (dur < 60)
			result += dur + " second";
		else {
		dur /= 60L; // Minutes
		if (dur < 60)
			result += dur + " minute";
		else {
		dur /= 60L; // Hours
		if (dur < 24)
			result += dur + " hour";
		else {
		dur /= 24L; // Days
		if (dur < 30)		//Precision isn't necessary; use avg month length
			result += dur + " day";
		else {
		dur /= 30L; // Months
		if (dur < 12)
			result += dur + " month";
		else {
		dur /= 12L; // Years
		result += dur + " year";
		}}}}}

		if (dur != 1)
			result += "s";
		return result;
	}

	void connect() {

		String[] channels;
		for (int i = 0; i < data.getNumNetworks(); i++) {
			log("Network: " + data.getNetwork(i) + " " + data.getPort(i) + " : " + data.getName(i));
			
			//Quickly build a channel list.
			channels = new String[data.getNumChannels(i)];
			for (int j = 0; j < channels.length; j++)
				channels[j] = data.getChannel(i, j);

			connections[i] = new IRCConnection(this, i);
			//Try CONNECT_ATTEMPT times, or until connected
			for (int j = 0; !connections[i].isConnected() && j < IRCConnection.CONNECT_ATTEMPTS; j++)
				try {
					connections[i].connect(channels, data.getNetwork(i), data.getPort(i), data.getName(i));
				} catch (UnknownHostException ioe) {
					log("Connection failed: Host not found: " + ioe.getMessage() + ". Giving up.");
					connections[i].disconnect();
					break;
				} catch (IOException ioe) {
					log("Connection attempt " + (j+1) + " failed: " + ioe.getMessage() + ". " +
							(j < IRCConnection.CONNECT_ATTEMPTS-1?"Trying again.":"Giving up."));
					log(ioe.toString());
					connections[i].disconnect();
				}
		}
		
	}

	void poll() {
		for (int i = 0; i < connections.length; i++) {
			IRCIO io = connections[i].getIRCIO();
			try {
				if (!data.getName(i).equals(io.getName()) &&
						System.currentTimeMillis() > lastNickAttempt + 60000) { //If we didn't get the nick we wanted
					lastNickAttempt = System.currentTimeMillis();
					io.changeNick(data.getName(i));
				}
				io.poll();
			} catch (IOException ioe) {
				logerror("Couldn't poll for input on connection to " + io.getName() + ": " + ioe.getMessage());
				log("Reconnecting.");
				try {
					io.connect();
					io.login();
				} catch (IOException ioe2) {
					logerror("Couldn't reconnect: " + ioe2.getMessage());
					io.disconnect();
					//broadcast(io.getName() + " died. :(");
				}
			}
		}
		checkForTimedMessages(connections);
		if (System.currentTimeMillis() > CHECK_ANNOUNCE_WAIT + lastCheckAnnounce) {
			data.checkAnnouncements(connections);
			lastCheckAnnounce = System.currentTimeMillis();
		}
		
		try {
			Thread.sleep(500);
		} catch (InterruptedException ie) {}
	}

	private boolean iregex(String pattern, String string) {
		return SephiaBotData.iregex(pattern, string);
	}
	
	private boolean iequals(String str1, String str2) {
		return SephiaBotData.iequals(str1, str2);
	}

	//Check if this person has any messages
	public void checkForMessages(IRCConnection con, String nick, String host, String recipient) {
		User user = data.getUserByHost(host);
		int totalMessages = 0;

		// Don't remind again if they just saw it < MESSAGE_STALE_DUR ago
		data.removeRecentMessages(nick, user);
		Message messages[] = data.getMessagesByReceiver(nick, user, true);
		if (messages.length == 0)
			return;

		for (int i = 0; i < messages.length; i++) {
			Message message = messages[i];
			if (!message.notified)
				continue;
			if (totalMessages >= 5) {
				con.getIRCIO().privmsg(recipient, "You have more messages.");
				return;
			}
			totalMessages++;
			String sender = message.sender;
			if (iequals(message.sender, message.target))
				sender = "yourself";
			con.getIRCIO().privmsg(recipient, nick + ", message from " + sender + " [" + makeTime(message.timeSent) + " ago]: " + message.message);
			data.removeMessage(message);
		}
	}

	//Announce any messages that have just happened or that haven't been
	// announced yet.
	//This function is called in the poll loop. It must not take long.
	public void checkForTimedMessages(IRCConnection[] connections) {
		Message messages[] = data.getPendingMessages();
		if (messages.length <= 0)
			return;

		for (int i = 0; i < messages.length; i++) {
			Message message = messages[i];
			IRCChannel channel = null;
			//Find the User this message's target nick belongs to, if any
			User target = data.getUserByName(message.target); // Might be null
			IRCConnection con = null;

			String sender = message.sender;
			if (iequals(message.sender, message.target))
				sender = "yourself";

			//Leave a message in the user's Home, if specified,
			// search for the last server/channel they spoke in,
			// or just find them anywhere.
			if (target != null) {
				if (target.home != null) {
					// They have a home set
					for (int k = 0; k < connections.length; k++) {
						con = connections[k];
						channel = con.getServer().findChannel(target.home);
						if (channel != null)
							break;
					}
					if (channel == null) {
						// Couldn't find the user's home channel.
						// Reset home.
						log("Can't find home '" + target.home + " for " +
								target + ".  Removing Home.");
						//TODO: We should maybe restore the home later, in
						//		case it's gone from a missing connection
						//		that could be restored.
						target.home = null;
						return;
					}
				}
				// home might have changed. Check again.
				if (target.home == null && target.lastChannel != null) {
					// Channel where they last spoke
					channel = target.lastChannel;
					con = channel.myServer.myConnection;
				}
			}
			// If we haven't found them yet, look in every channel
			if (con == null || channel == null) {
				search:
				for (int k = 0; k < connections.length; k++) {
					con = connections[k];
					IRCChannel[] channels = con.getServer().channels;
					for (int j = 0; j < channels.length; j++)
						if (channels[j].userInChannel(target, message.target)) {
							channel = channels[j];
							break search;
						}
				}
			}
			// Send the message unless they're not in a channel
			if (con != null && channel != null) {
				con.getIRCIO().privmsg(channel.name, message.target +
					", message from " + sender + " [" +
					makeTime(message.timeSent) + " ago]: " + message.message);
				message.notified = true;
				message.timeNotified = System.currentTimeMillis();
				data.writeData();
				data.findNextMessageTime();
			}
		}
	}
	
	public void messagePrivEmote(IRCConnection con, String nick, String host, String recipient, String msg) {
		String log;
		// This will be null, unless the message is to a channel.  Always check.
		IRCChannel channel = con.getServer().findChannel(recipient);

    if (channel != null)
      channel.lastActivity = System.currentTimeMillis(); // now
		
		if (iregex("^"+data.getName(con.getIndex())+"-*$", recipient))
			recipient = nick;

		log = "* " + nick + " " + msg;

		con.logfile(recipient, log);
		
		String botname = data.getName(con.getIndex());
		msg = msg.trim();

		data.updateUserTimes(nick, host, con.getServer(), recipient);
		checkForMessages(con, nick, host, recipient);
		checkForBlacklist(con, nick, host, recipient);
		
		if (System.currentTimeMillis() > nextWho) { //!spam
			nextWho = System.currentTimeMillis() + SPAM_WAIT;
						
			if (iregex("hugs " + botname, msg)) {
				if (data.isVino(host))
					con.getIRCIO().privemote(recipient, "hugs Vino!");
				else if (censor(con))
					con.getIRCIO().privemote(recipient, "hugs " + nick + "!");
				else
					con.getIRCIO().privmsg(recipient, "Get the fuck off.");
			} else if (iregex("(slaps|smacks|hits|punches|kicks) " + botname, msg)) {
				String action = msg.replaceFirst(" .*", "");
				con.getIRCIO().privemote(recipient, action + " back harder!");
			} else if (iregex("p[ea]ts " + botname, msg)) {
				con.getIRCIO().privemote(recipient, "purrs.");
			} else if (iregex("pokes " + botname, msg)) {
				boolean tickle = new Random().nextBoolean();
				if (tickle == true) {
					con.getIRCIO().privemote(recipient, "laughs.");
				} else {
					con.getIRCIO().privmsg(recipient, "Ouch!"); 
				}
			} else if (iregex("tickles " + botname, msg)) {
				User user = data.getUserByNick(connections, nick);
				if (user != null) { 
					con.getIRCIO().privemote(recipient, "giggles."); 
				} else {

					con.getIRCIO().privemote(recipient, "slaps " + nick + ".");
				}
			} else if (iregex(botname, msg) &&
					iregex("bot[ -]*snack", msg)) {
				con.getIRCIO().privmsg(recipient, "Yaaaaaay!");
			}
			return;
		}
	}

	public void messagePrivMsg(IRCConnection con, String nick, String host, String recipient, String origmsg) {
		boolean pm = false;
		String log;
		String msg = origmsg;
		// This will be null, unless the message is to a channel.  Always check.
		IRCChannel channel = con.getServer().findChannel(recipient);

    if (channel != null)
      channel.lastActivity = System.currentTimeMillis(); // now

		if (iregex("^"+data.getName(con.getIndex())+"-*$", recipient)) {
			recipient = nick;
			pm = true;
		}

		log = "<" + nick + "> ";
		log += msg.substring(0, msg.length());

		con.logfile(recipient, log);

		msg = msg.trim();

		data.updateUserTimes(nick, host, con.getServer(), recipient);
		checkForMessages(con, nick, host, recipient);
		checkForBlacklist(con, nick, host, recipient);
		if (channel != null)
			channel.updateHistory(nick, msg);
		
		String name = data.getName(con.getIndex());

		StringTokenizer tok = new StringTokenizer(msg, ",: ");
		String botname;
		if (!pm && tok.hasMoreElements()) {
			botname = tok.nextToken();
		} else {
			botname = "";
		}

		if (pm || talkingToMe(msg, data.getName(con.getIndex()))) {

			//Remove the bot's name
			if (!pm)
				msg = msg.replaceFirst(botname + "[,: ]+", "");

			if (iregex("bring out the strapon", msg)) {
				con.getIRCIO().privemote(recipient, "steps forward with a large strapon and begins mashing potatoes.");
				return;
			}

			msg = data.removePunctuation(msg, ".?!");

			//BEGIN COLLOQUIAL COMMANDS
			//These commands can be used anywhere if the bot's name is spoken first.
			if (iregex("^who are you\\W*$", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privmsg(recipient, "I am an advanced SephiaBot channel bot.");
					con.getIRCIO().privmsg(recipient, "I'll kick your " + (censor(con)?"butt":"ass") + " in days that end in 'y'.");
					con.getIRCIO().privmsg(recipient, "I was written by Vino. Vino rocks.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if ( iregex("^what does marsellus wallace look like\\W*$", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privmsg(recipient, "He's black.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^who (wrote|made|programmed|coded|created) you\\W*$", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privmsg(recipient, "I was written by Vino. Vino rocks.");
					con.getIRCIO().privmsg(recipient, "Nilbus helped too.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^who('s| is) here\\W*$", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam

					int channum = channelNumber(con.getIndex(), recipient);
					if (channum == -1 || pm) {
						con.getIRCIO().privmsg(recipient, "It's just you and me in a PM, buddy.");
						nextWho = System.currentTimeMillis() + SPAM_WAIT;
					} else {
						StringBuffer buf = new StringBuffer("Users in this channel:");
						IRCUser current = con.getServer().channels[channum].users;
						for (IRCUser curr = con.getServer().channels[channum].users; curr != null; curr = curr.next) {
							buf.append(" " + curr.name);
						}
						con.getIRCIO().privmsg(recipient, buf.toString());
						nextWho = System.currentTimeMillis() + SPAM_WAIT;
					}
				}
				return;
			} else if (iregex("^who('s| is)", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					String whoisName = msg.substring(msg.lastIndexOf(' ')+1, msg.length());
					if (talkingToMe(whoisName, data.getName(con.getIndex())))
						con.getIRCIO().privmsg(recipient, "I am an advanced SephiaBot channel bot.");
					else {
						User target = data.getUserByName(whoisName);
						if (target == null)
							con.getIRCIO().privmsg(recipient, "Nobody important.");
						else
							con.getIRCIO().privmsg(recipient, target.description);
					}
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^(are you|you( a|')re) (sexy|h(o|aw)t|beautiful|awesome|cool|swell)", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					String compliment = data.iregexFind("(sexy|h(o|aw)t|beautiful|awesome|cool|swell)", msg);
					if (censor(con))
						if (compliment.equals("sexy"))
							con.getIRCIO().privmsg(recipient, "I'm too sexy for my shirt!");
						else
							con.getIRCIO().privmsg(recipient, "I am so " + compliment + ".");
					else
						con.getIRCIO().privmsg(recipient, "Fuck yes.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (!censor(con) && iregex("^wan(na |t to )cyber", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					User user = data.getUserByNick(connections, nick);
					if (data.isVino(host) || user != null && iequals(user.userName, "Yukie")) {
						con.getIRCIO().privmsg(recipient, "Take me, " + nick + "!");
					} else {
						con.getIRCIO().privmsg(recipient, "Fuck no.");
					}
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^wh?[aeu]re?('?[sz]| i[sz]| si| be?)( m(a[ih]|y))?", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					String targetName = msg.substring(msg.lastIndexOf(' ')+1, msg.length());
					targetName = data.removePunctuation(targetName, "!?,");
					boolean foundAway = false;
					if (iregex("eve?ry(b(o|ud)dy|(1|one?))", targetName)) {
						//Find out where everybody is and tell the channel.
						for (int i = 0; i < data.getNumUsers(); i++) {
							User user = data.getUser(i);
							//Do not display away message if the person has not logged in for a while.
							if (user.away != null && data.timeInWeeks(user.lastTalked, System.currentTimeMillis()) < 1) {
								con.getIRCIO().privmsg(recipient, user.userName + " has been " + user.away + " for " + makeTime(user.leaveTime) + ".");
								foundAway = true;
							}
						}
						if (!foundAway)
							con.getIRCIO().privmsg(recipient, "Everyone is present and accounted for.");
						return;
					}
					if (iequals(targetName, botname))
						return;
					User target = data.getUserByNick(connections, targetName);
					if (target == null) {
						con.getIRCIO().privmsg(recipient, "Like I know.");
						return;
					} else
						targetName = target.userName; //use correct caps
					if (target.away == null) {
						if (target.lastTalked > 0)
							con.getIRCIO().privmsg(recipient, "I don't know, the last time they said anything was " + makeTime(target.lastTalked) + " ago.");
						else
							con.getIRCIO().privmsg(recipient, "I don't know.");
					} else {
						con.getIRCIO().privmsg(recipient, targetName + " is " + target.away + ". " + targetName + " has been gone for " + makeTime(target.leaveTime) + ".");
					}
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^who am i", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					User target = data.getUserByHost(host);
					if (data.isVino(host)) {
						con.getIRCIO().privmsg(recipient, "Daddy!");
						con.getIRCIO().privemote(recipient, "hugs " + nick + ".");
					} else if (target != null) {
						con.getIRCIO().privmsg(recipient, "Someone I know.");
					} else {
						con.getIRCIO().privmsg(recipient, "Nobody important.");
					}
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^who('| i)?s your daddy", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privmsg(recipient, "Vino's my daddy, ugh! Spank me again Vino!");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^knock knock", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privmsg(recipient, "Who's there?");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (!censor(con) && iregex("i suck dick", msg)) {
				if (System.currentTimeMillis() > nextWho) { //!spam
					con.getIRCIO().privmsg(recipient, "Yeah, we know you do.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (!censor(con) && iregex("words of wisdom", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					String phrase = data.randomPhrase("wordsofwisdom.txt");
					if (phrase != null)
						con.getIRCIO().privmsg(recipient, phrase);
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("roll (the )?dice", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					Random rand = new Random();
					int dice = rand.nextInt(5)+2;
					int sides = rand.nextInt(5)+6;
					con.getIRCIO().privemote(recipient, "rolls " + dice + "d" + sides + " dice and gets " + (dice*sides+1) + ".");
					if (!data.isVino(host)) {
						con.getIRCIO().privemote(recipient, "kills " + nick + ".");
					} else {
						con.getIRCIO().privemote(recipient, "hugs " + nick + ".");
					}
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("do a little dance", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privemote(recipient, "makes a little love.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("excuse", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					String excuse = data.randomPhrase("excuses.txt");
					if (excuse != null)
						con.getIRCIO().privmsg(recipient, "Your excuse is: " + excuse);
					else
						con.getIRCIO().privmsg(recipient, "I can't think of one. :(");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^why", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					String why = data.randomPhrase("excuses.txt");
					if (why != null)
						con.getIRCIO().privmsg(recipient, why);
					else
						con.getIRCIO().privmsg(recipient, "I don't know, sorry. :(");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("bot[ -]*snack", msg)) {
				if (System.currentTimeMillis() > nextWho) {	//!spam
					con.getIRCIO().privmsg(recipient, "Yaaaaaay!");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
				}
				return;
			} else if (iregex("^ping\\W*$", msg)) {
				con.getIRCIO().privmsg(recipient, nick + ", pong");
				return;
				
			//BEGIN COMMAND SECTION.
			//These are one-word commands only. The command is the first thing you say after the bot name. botname, command arguments.
			} else if (tok.hasMoreElements()) {
				String cmd = tok.nextToken(" ");
				if (tok.hasMoreElements() && (cmd.startsWith(",") || cmd.startsWith(":"))) { 
					cmd = tok.nextToken(" ");
				}
				if (iequals("kill", cmd)) {
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "KILL! KILL! KILL!");
						return;
					}
					String killed = tok.nextToken(" ");
					User killerUser = data.getUserByHost(host);
					User killedUser = data.getUserByNick(connections, killed);
					if ((killerUser == null || (killedUser != null && killedUser.memberType > killerUser.memberType)) && !data.isVino(host)) {
						con.getIRCIO().privemote(recipient, "giggles at " + nick);
					} else if (iequals(killed, botname)) {
						con.getIRCIO().privmsg(recipient, ":(");
					} else {
						int killedAccess = con.getAccess(killed, channelNumber(con.getIndex(), recipient));
						if (killedAccess != -1) {
							con.getIRCIO().privmsg(recipient, "It would be my pleasure.");
							con.getIRCIO().kick(recipient, killed, "This kick was compliments of " + killerUser.userName + ". Have a nice day.");
						} else if (iequals("yourself", killed))	{ //reboot
							if (data.isAdmin(host)) {
								con.getIRCIO().privemote(recipient, "gags and passes out.");
								shutdown(true);
							} else {
								con.getIRCIO().privmsg(recipient, "No.");
							}
						} else if (iregex("^(reminder|message)$", killed)) {
							killed = killed.toLowerCase();
							if (!tok.hasMoreElements()) {
								con.getIRCIO().privmsg(recipient, "Which message? I need a number");
								return;
							}
							try {
								// Index starts at 0, first displayed message is 1
								int msgIndex = Integer.parseInt(tok.nextToken()) - 1;
								User user = data.getUserByHost(host);
								Message messages[] = data.getMessagesBySender(nick, user);
								if (msgIndex < 0) // try looping around once
									msgIndex += messages.length + 1; // +1 adjust for skipping 0
								if (msgIndex >= messages.length || msgIndex < 0) {
									con.getIRCIO().privmsg(recipient, "You don't have that many messages.");
									return;
								}
								Message message = messages[msgIndex];
								String timeToArrive;
								if (message.timeToArrive == 0)
									timeToArrive = "";
								else if (System.currentTimeMillis() > message.timeToArrive)
									timeToArrive = " for " + makeTime(message.timeToArrive) + " ago";
								else
									timeToArrive = " for " + makeTime(message.timeToArrive) + " from now";
								con.getIRCIO().privmsg(recipient, "Message removed for " + message.target + timeToArrive + ": " + message.message);
								data.removeMessage(message);
							} catch (NumberFormatException nfe) {
								con.getIRCIO().privmsg(recipient, "...if you can call that a number.");
							}
							return;
						} else
							con.getIRCIO().privmsg(recipient, "Kill who what now?"); 
						return;
					}
					return;
				} else if (iregex("^(remind|tell|ask)$", cmd)) {
					// Bit, remind [person] ([at time] || [on day]) OR ([in duration]) [to OR that OR about] [something]
					// Bit, remind [person] [to OR that OR about] [something] ([at time] || [on day]) OR ([in duration])
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "Tell who what when?");
						return;
					}
					String target = tok.nextToken(" ");
					boolean myself = false;
					String sender = nick;
					target = data.removePunctuation(target, ".!,:");
					if (iregex("(me|myself)", target)) {
						myself = true;
						target = nick;
					}
					//If the target is logged in, send the message to his username instead so he will always get it if he is logged in.
					User targetUser = data.getUserByNick(connections, target);
					//if that didn't work, try by name
					if (targetUser == null)
						targetUser = data.getUserByName(target);
					//did we find a user?
					if (targetUser != null)
						target = targetUser.userName;
					//If the sending user is logged in, send the messages as his username instead so that all the messages are sent by the same user.
					User senderUser = data.getUserByHost(host);
					if (senderUser != null)
						sender = senderUser.userName;
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "Tell " + target + " when what?");
						return;
					}
					try {
						Message message = data.addMessage(target, tok.nextToken("").substring(1), sender);

						con.getIRCIO().privmsg(recipient, "I'll tell " + 
							(myself?"you":target) +" "+ message.getTimeExpression());
					} catch (NumberFormatException nfe) {
						con.getIRCIO().privmsg(recipient, nfe.getMessage());
					}
					return;
				} else if (iregex("^(butt?)?se(x|ck[sz])$", cmd)) {
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privemote(recipient, "anally rapes " + nick + ".");
						return;
					}
					String sexed = tok.nextToken(" ");
					if (iregex("^vino", sexed)) {
						con.getIRCIO().privemote(recipient, "screams as Vino penetrates every orifice of her body!");
					} else if (iequals(botname, sexed)) {
						con.getIRCIO().privemote(recipient, "furiously works the potato masher!");
					} else {
						int sexedAccess = con.getAccess(sexed, channelNumber(con.getIndex(), recipient));
						if (sexedAccess == -1) {
							sexed = nick;
						}
						con.getIRCIO().privemote(recipient, "anally rapes " + sexed + ".");
					}
					return;
				} else if (iregex("^re(boot|start)$", cmd)) {
					if (data.isAdmin(host)) {
						con.getIRCIO().privmsg(recipient, "Be right back.");
						shutdown(true);
					} else
						con.getIRCIO().privmsg(recipient, "No.");
					return;
				} else if (iregex("^(shutdown|die|leave)$", cmd)) {
					if (data.isAdmin(host)) {
						con.getIRCIO().privmsg(recipient, "Goodbye. :(");
						shutdown(false);
					} else {
						con.getIRCIO().privmsg(recipient, "No.");
					}
					return;
				} else if (iequals("reload", cmd)) {
					if (data.isAdmin(host)) {
						data.parseConfig();
						con.getIRCIO().privmsg(recipient, "Done.");
					} else {
						con.getIRCIO().privmsg(recipient, "No.");
					}
					return;
				} else if (iequals("save", cmd)) {
					if (data.isAdmin(host)) {
						data.writeData();
						con.getIRCIO().privmsg(recipient, "Done.");
					} else {
						con.getIRCIO().privmsg(recipient, "No.");
					}
					return;
				} else if (iequals("listhosts", cmd)) {
					User user = data.getUserByHost(host);
					if (user == null)
						return;
					String buffer = "Hosts logged in as " + user.userName + ":";
					for (int i = 0; i < 10; i++)
						if (user.hosts[i] != null && user.hosts[i].length() > 0)
							buffer += " " + (i+1) + ": " + user.hosts[i];
					con.getIRCIO().privmsg(nick, buffer);
					return;
				} else if (iequals("logout", cmd)) {
					User user = data.getUserByHost(host);
					if (user == null)
						return;
					if (!tok.hasMoreElements()) {
						for (int i = 0; i < 10; i++) {
							if (user.hosts[i] != null && user.hosts[i].equals(host)) {
								data.logout(user, i);
								con.getIRCIO().privmsg(nick, "It's too bad things couldn't work out.");
								return;
							}
						}
						con.getIRCIO().privmsg(nick, "Your host is not logged in.");
						return;
					}
					try {
						int i = Integer.parseInt(tok.nextToken("").trim())-1;
						if (i < 0 || i >= 10)
							return;
						if (user.hosts[i] == null || user.hosts[i].length() <= 0)
							con.getIRCIO().privmsg(nick, "That host is not logged in.");
						else {
							con.getIRCIO().privmsg(nick, "It's too bad things couldn't work out.");
							data.logout(user, i);
							data.logout(user, i);
							user.hosts[i] = null;
						}
					} catch (NumberFormatException nfe) {
					}
					return;
				} else if (iequals("login", cmd)) {
					if (tok.countTokens() < 2) {
						con.getIRCIO().privmsg(nick, "Yeah. Sure. Whatever.");
						return;
					}
					String login = tok.nextToken(" ").trim();
					String passwd = tok.nextToken("").trim();
					if (data.validateLogin(login, passwd)) {
						int i, userID = -1;
						User user = data.getUserByName(login);
						if (user == null) {	//WTFException
							con.getIRCIO().privmsg(nick, "WTF? Tell Vino you saw this.");
							return;
						}
						// If getUserByHost returns non-null then the user is logged into this user already,
						// or is logged in as another user and may not re-log in.
						User ghost = data.getUserByHost(host);
						if (ghost != null) {
							con.getIRCIO().privmsg(nick, "Silly you. You're already logged in as " + ghost.userName);
							return;
						}
						data.loginUser(user, host);
						if (data.isVino(user))
							con.getIRCIO().privmsg(nick, "Hi, daddy! :D");
						else
							con.getIRCIO().privmsg(nick, "What's up " + user.userName + "?");
						data.writeData();
					} else {
						con.getIRCIO().privmsg(nick, "No cigar.");
						log("Failed login attempt by " + nick + "!" + host + " with " + login + "/" + passwd + ".");
					}
					return;
				} else if (iregex("^i('|\"| a)?m$", cmd)) {
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "You're what?");
						return;
					}
					User user = data.getUserByHost(host);
					if (user == null) {
						con.getIRCIO().privmsg(recipient, "I don't care.");
						return;
					}
					String location = tok.nextToken("").trim();
					if (iregex("^ba+c?k", location)) {
						if (user.away == null) {
							con.getIRCIO().privmsg(recipient, "Of course you are honey.");
						} else {
							con.getIRCIO().privmsg(recipient, "Welcome back! You've been away for " + makeTime(user.leaveTime) + ".");
							user.away = null;
						}
					} else {
						con.getIRCIO().privmsg(recipient, "Have fun!");
						//Remove punctuation from the end
						location = data.removePunctuation(location, ".!,?");
						user.away = location.replaceAll("\"", "'");
						user.leaveTime = System.currentTimeMillis();
					}
					data.writeData();
					return;
				} else if (iregex("^(messages|reminders)$", cmd)) {
					cmd = cmd.toLowerCase();
					User user = data.getUserByHost(host);
					int lastIndex, firstIndex = 0;
					if (tok.hasMoreElements()) {
						try {
							firstIndex = Integer.parseInt(tok.nextToken())-1;
							if (firstIndex < 1)
								firstIndex = 1;
						} catch (NumberFormatException nfe) {
						}
					}
					Message messages[] = data.getMessagesBySender(nick, user);
					if (messages.length == 0) {
						con.getIRCIO().privmsg(recipient, "You haven't sent any messages.");
						return;
					}
					if (firstIndex >= messages.length) {
						con.getIRCIO().privmsg(recipient, "You don't have that many messages.");
						return;
					}
					lastIndex = firstIndex + 5;
					if (lastIndex >= messages.length)
						lastIndex = messages.length-1;
					con.getIRCIO().privmsg(nick, "You have sent the following messages:");
					for (int i = firstIndex; i <= lastIndex; i++) {
						Message message = messages[i];
						String target = message.target;
						if (iequals(message.target, nick))
							target = "you";
						String timeToArrive;
						if (message.timeToArrive == 0) // No destination time; regular message
							timeToArrive = "";
						else if (System.currentTimeMillis() > message.timeToArrive)
							timeToArrive = ", " + makeTime(message.timeToArrive) + " ago";
						else
							timeToArrive = ", " + makeTime(message.timeToArrive) + " from now";
						con.getIRCIO().privmsg(nick, "Message " + (i+1) + ": For " + target + timeToArrive + ": " + message.message);
					}
					return;
				} else if (iregex("^(say|do|emote)$", cmd)) {
					if (!data.isAdmin(host)) {
						con.getIRCIO().privmsg(recipient, "No.");
						return;
					}
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "Say what?!?");
						return;
					}
					String firstWord = tok.nextToken();
					String everythingElse = "";
					String targetChannel = recipient;
					if (iequals("in", firstWord) && tok.hasMoreTokens()) {
						targetChannel = tok.nextToken();
						if (tok.hasMoreElements()) {
							firstWord = tok.nextToken();
						} else {
							con.getIRCIO().privmsg(targetChannel, "Say what?!?");
							return;
						}
					}
					if (tok.hasMoreTokens())
						everythingElse = tok.nextToken("");
					
					//Check recipient
					IRCChannel chan = null;
					IRCConnection sayCon = con;
					chan = sayCon.getServer().findChannel(targetChannel); //first try the same server
					if (chan == null)
						for (int i = 0; i < connections.length; i++) { //search other servers
							if (connections[i] != sayCon) { //the current one has already been searched
								chan = connections[i].getServer().findChannel(targetChannel);
								if (chan != null) {
									sayCon = connections[i];
									break;
								}
							}
						}
					if (chan == null) {
						con.getIRCIO().privmsg(recipient, "I'm not in that channel.");
						return;
					}
					
					if (iequals("say", cmd))
						sayCon.getIRCIO().privmsg(chan.name, firstWord + everythingElse);
					else
						sayCon.getIRCIO().privemote(chan.name, firstWord + everythingElse); return; //TODO: Make mode setting colloquial
				} else if (iequals("mode", cmd)) {
					if (!data.isAdmin(host)) {
						con.getIRCIO().privmsg(recipient, "No.");
						return;
					}
					String inchannel = recipient;
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "What channel?");
						return;
					}
					inchannel = tok.nextToken();
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "Who?");
						return;
					}
					String who = tok.nextToken();
					String mode;
					if (!tok.hasMoreElements()) {
						con.getIRCIO().privmsg(recipient, "What mode?");
						return;
					} else mode = tok.nextToken();
					System.out.println("MODE " + inchannel + " " + mode + " " + who + "\n");
					con.getIRCIO().setMode(who, inchannel, mode);
					return;
				} else if (iregex("^(last|seen)$", cmd)) {
					if (!tok.hasMoreTokens()) {
						con.getIRCIO().privmsg(recipient, "When did I last see who?");
						return;
					}
					nick = tok.nextToken();
					User target = data.getUserByNick(connections, nick);
					if (target == null)
						con.getIRCIO().privmsg(recipient, "I wasn't really paying attention to " + nick + ".");
					else
						con.getIRCIO().privmsg(recipient, "Last time I saw " + target.userName + " was " + makeTime(target.lastTalked) + " ago.");
					return;
				} else if (iequals("channels", cmd)) {
					User user = data.getUserByHost(host);
					if (user == null) {
						con.getIRCIO().privmsg(nick, "It's a secret.");
						return;
					}
					for (int i = 0; i < connections.length; i++) {
						IRCIO server = connections[i].getIRCIO();
						String[] channels = server.getChannels();
						con.getIRCIO().privmsg(nick, "Server: " + server.getNetwork());
						for (int j = 0; j < channels.length; j++)
							con.getIRCIO().privmsg(nick, "  " + channels[j]);
						con.getIRCIO().privmsg(nick, "And that's all!");
					}
					return;
				}
			}	
		}
		//Everything above here should return if it does something.

		//Commands where the bot's name can appear at the end
		if (pm || talkingToMe(origmsg, data.getName(con.getIndex())) || iregex(name + "[^a-zA-Z]*$", msg)) {
			if (!censor(con)) {
				if (iregex("fuck you", msg)) {
					if (System.currentTimeMillis() > nextWho) {	//!spam
						con.getIRCIO().privmsg(recipient, "Fuck you too, buddy.");
						nextWho = System.currentTimeMillis() + SPAM_WAIT;
						return;
					}
				} else if (iregex("screw you", msg)) {
					if (System.currentTimeMillis() > nextWho) {	//!spam
						con.getIRCIO().privmsg(recipient, "Screw you too, buddy.");
						nextWho = System.currentTimeMillis() + SPAM_WAIT;
						return;
					}
				} else if (iregex("you suck", msg)) {
					if (System.currentTimeMillis() > nextWho) {	//!spam
						con.getIRCIO().privmsg(recipient, "I suck, but you swallow, bitch.");
						nextWho = System.currentTimeMillis() + SPAM_WAIT;
						return;
					}
				}
			}
			if (data.matchHellos(msg)) {
				if (System.currentTimeMillis() > nextHi) {	//!spam
					con.getIRCIO().privmsg(recipient, data.getRandomHelloReply());
					nextHi = System.currentTimeMillis() + 500;
					return;
				}
			} else if (iregex("(thank( ?(yo)?u|[sz])\\b|\\bt(y|hn?x)\\b)", msg)) {
				if (System.currentTimeMillis() > nextWho) { //!spam
					con.getIRCIO().privmsg(recipient, "No problem.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
					return;
				}
			} else if (iregex("bounc[ye]", msg)) {
				if (System.currentTimeMillis() > nextWho) { //!spam
					con.getIRCIO().privmsg(recipient, "Bouncy, bouncy, bouncy!");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
					return;
				}
			} else if (iregex("(right|correct)", msg)) {
				if (System.currentTimeMillis() > nextWho) { //!spam
					con.getIRCIO().privmsg(recipient, "Absolutely.");
					nextWho = System.currentTimeMillis() + SPAM_WAIT;
					return;
				}
			} else if (iregex("(custard|flavor( of? the? day)?|fotd)", msg)) {
				int offset = 0;
				if (iregex("tomorrow", msg)) {
					offset++;
				} else {
					ParseTime pt = new ParseTime();
					try {
						pt.textToTime(msg);
						// If time expression is found, our answer might be wrong.
						con.getIRCIO().privmsg(recipient, "I don't know. Ask me " + pt.getTimeExpression() + ".");
						return;
					} catch (WTFException e) {
						// Continue as normal if no time expression found
					}
				}
				if (Custard.getMonth() == -1)
					con.getIRCIO().privmsg(recipient, "Hold on, I'll check.");
				con.getIRCIO().privmsg(recipient, Custard.flavorOfTheDay(offset));
				return;
			}

			// We were spoken to, but don't understand what was said.
			// If this appears on a real command, then we forgot a return statement
			// with that command.
			if (pm)
				con.getIRCIO().privmsg(recipient, "What?");
		//Wasn't talking to the bot
		} else if (!pm && channel != null && channel.parrotOK()) {
				channel.setLastRepeat(channel.getHistory(0));
				con.getIRCIO().privmsg(recipient, channel.getHistory(0));
		}
	}

	public boolean talkingToMe(String msg, String name) {
		int nameEnd = name.length() < 4 ? name.length() : 4;
		return iregex("^"+name.substring(0, nameEnd), msg);
	}

	public void checkForBlacklist(IRCConnection con, String nick, String host, String channelName) {
		//Check for blacklisted nicks.
		if (data.checkForBlacklist(nick)) {
			con.getIRCIO().ban(channelName, nick, host);
			con.getIRCIO().kick(channelName, nick, "You have been blacklisted. Please never return to this channel.");
			return;
		}
	}
		
	public void messageChannelJoin(IRCConnection con, String nick, String host, String channelName) {

		String log;

		log = "--> " + nick + " has joined " + channelName;

		con.logfile(channelName, log);

		//Say something as you enter the channel!
		nick = nick.replaceFirst("-+$", "");
		if (greet && iequals(nick, data.getName(con.getIndex()))) {
			String greeting = data.getGreeting(con.getIndex(), con.getCurrentChannel());
			if (greeting != null && greeting.length() > 0 &&
					// Do not greet if greeting is "-"
					!greeting.equals("-"))
				con.getIRCIO().privmsg(channelName, greeting);
		}

		if (iequals("metapod\\", nick)) {
			con.getIRCIO().privmsg(channelName, "Heya meta.");
		}

		if (iequals("luckyremy", nick)) {
			con.getIRCIO().privemote(channelName, "salutes as Remy enters.");
		}

		//Set lastChannel
		User user = data.getUserByHost(host);
		IRCChannel ircchan = con.getServer().findChannel(channelName);
		if (user != null && ircchan != null)
			user.lastChannel = ircchan;

		//blacklist, check for messages
		checkForMessages(con, nick, host, channelName);
		checkForBlacklist(con, nick, host, channelName);

		//Add user to channel's user list
		if (ircchan != null) {
			ircchan.addUser(nick, host, IRCServer.ACCESS_NONE);
		} else {
			logerror("couldn't find channel name in SephiaBot.messageChannelJoin");
			return;
		}
	}

	public void messageChannelPart(IRCConnection con, String nick, String host, String channelName, String message, boolean kicked) {

		String log;

		String how = (kicked?"left ":"been kicked from ");
		log = "<-- " + nick + " has " + how + channelName;
		if (message != null) {
			log += " (" + message + ")";
		}
		con.logfile(channelName, log);

		//unset lastChannel if leaving
		User user = data.getUserByHost(host);
		IRCChannel ircChan = con.getServer().findChannel(channelName);
		if (user != null && ircChan == user.lastChannel)
			user.lastChannel = null;

		checkForBlacklist(con, nick, host, channelName);

		//remove user from channel's user list
		IRCChannel ircchan = con.getServer().findChannel(channelName);
		if (ircchan != null) {
			ircchan.deleteUser(nick);
		} else {
			logerror("couldn't find channel name in SephiaBot.messageChannelPart");
			return;
		}
	}

	public void messageQuit(IRCConnection con, String nick, String host, String msg) {
		String log;
		log = "<-- " + nick + " has quit ";
		if (msg != null) {
			log += " (" + msg + ")";
		}
		con.logfile(null, log);

		//unset lastChannel
		User user = data.getUserByHost(host);
		if (user != null)
			user.lastChannel = null;

		//remove user from all channels
		IRCChannel[] channels = con.getServer().channels;
		for (int i = 0; i < channels.length; i++)
			channels[i].deleteUser(nick);
	}

	public void messageNickChange(IRCConnection con, String nick, String host, String newname) {
		String log;
		log = "--- " + nick + " changed his name to " + newname;
		con.logfile(null, log);

		//Is this my nick?
		if (nick.equals(con.getIRCIO().getName()))
			con.getIRCIO().setName(newname);
		
		//update user's nick in all channels
		IRCChannel[] channels = con.getServer().channels;
		for (int i = 0; i < channels.length; i++)
			channels[i].updateUser(nick, newname, null, IRCServer.ACCESS_UNKNOWN);
	}

	public void messageModeChange(IRCConnection con, String nick, String host, String channelName, String mode, String recipient) {

		String log;

		log = "--- " + nick + " set mode " + mode + " on " + recipient;

		con.logfile(channelName, log);

		int access = IRCServer.ACCESS_UNKNOWN;
		if (mode.equalsIgnoreCase("-v") || mode.equalsIgnoreCase("-o"))
			access = IRCServer.ACCESS_NONE;
		else if (mode.equalsIgnoreCase("+o"))
			access = IRCServer.ACCESS_OP;
		else if (mode.equalsIgnoreCase("+v"))
			access = IRCServer.ACCESS_VOICE;

		//update user's nick in the channel's user list
		IRCChannel ircchan = con.getServer().findChannel(channelName);
		if (ircchan != null) {
			ircchan.updateUser(nick, null, null, access);
		} else {
			logerror("couldn't find channel name in SephiaBot.messageModeChange");
			return;
		}
	}

	public void messageChanList(IRCConnection con, String channelName, String list) {

		int channum = channelNumber(con.getIndex(), channelName);

		StringTokenizer tok = new StringTokenizer(list, " ");
//		int usersInWhois = 0;
//		String userhostString = "";
		
		con.getIRCIO().who(channelName);
		
		while (tok.hasMoreElements()) {
			String user = tok.nextToken();
			int access = IRCServer.ACCESS_UNKNOWN;
			if (user.startsWith("@")) {
				access = IRCServer.ACCESS_OP;
			} else if (user.startsWith("%")) {
				access = IRCServer.ACCESS_HALFOP;
			} else if (user.startsWith("+")) {
				access = IRCServer.ACCESS_VOICE;
			} else {
				access = IRCServer.ACCESS_NONE;
			}
			if (access > 0) {
				user = user.substring(1);
			}
			con.getServer().channels[channum].addUser(user, "", access);
			
		}

	}

	public void messageUserHosts(IRCConnection con, String users) {
		StringTokenizer tok = new StringTokenizer(users, " =");

hostFinder:
		while (tok.hasMoreElements()) {
			String name = tok.nextToken();

			//If no more elements, throw WTFException
			if (!tok.hasMoreElements())
				return;

			String host = tok.nextToken();
			host = host.substring(1, host.length());

			//For every channel, find users that fit this username and assign this host to them.
			for (int i = 0; i < con.getServer().channels.length; i++) {
				for (IRCUser curr = con.getServer().channels[i].users; curr != null; curr = curr.next) {
					if (curr.name.equals(name)) {
						curr.host = host;
						continue hostFinder;
					}
				}
			}
		}
	}

	public void messageWho(IRCConnection con, String userchannel, String usernick, String username, String host, String realname) {
		for (int i = 0; i < con.getServer().channels.length; i++) {
			if (con.getServer().channels[i].name.equalsIgnoreCase(userchannel)) {
				for (IRCUser curr = con.getServer().channels[i].users; curr != null; curr = curr.next) {
					if (curr.name.equalsIgnoreCase(usernick)) {
						curr.host = username + "@" + host;
						return;
					}
				}
				return;
			}
		}
	}
	
	public void messageReceived(IRCConnection con, String msg) {

	}

	public int channelNumber(int serverID, String channelName) {
		for (int channum = 0; channum < data.getNumChannels(serverID); channum++) {
			if (channelName.equalsIgnoreCase(data.getChannel(serverID, channum))) {
				return channum;
			}
		}
		return -1;
	}

	//Bot system log
	public void log(String log) {
		data.log(log);
	}

	//Error log
	public void logerror (String log) {
		log("SYSERR: " + log);
	}

	public String getLogdir() {
		return data.getLogdir();
	}

	public void broadcast(String message) {
		for (int i = 0; i < connections.length; i++) {
			IRCConnection con = connections[i];
			for (int j = 0; j < con.getServer().channels.length; j++) {
				IRCChannel chan = con.getServer().channels[j];
				if (message.startsWith("*"))
					connections[i].getIRCIO().privemote(chan.name, message.substring(1));
				else
					connections[i].getIRCIO().privmsg(chan.name, message);
			}
		}
	}
	
	public void shutdown(boolean reboot) {
		for (int i = 0; i < connections.length; i++) {
			connections[i].getIRCIO().quit(reboot?"Rebooting":"Quitting");
		}
		data.writeData();
		System.exit(reboot?1:0);
	}
  
  public SephiaBotData getData() {
    return data;
  }
}
