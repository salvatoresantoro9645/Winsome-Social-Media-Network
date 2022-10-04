# Winsome-Social-Media-Network
Winsome application is a Social Media Network inspired on "Steemit"

# Overview
Winsome is a Social Media Network with revenew on post rated, shared and commented by the network's users.
Each registered user can follow other user and can be followed.
Each user, during the registration phase must provide tags (max 5), charaterizing its own profile.
Moreover, each user can publish, comment, rate and make the "rewin" of posts.
Each user have his own blog, containing the posts of which he is the author or he has rewined, and its own feed, containing the post of the users of which he is follower.
The interaction between users and posts is the way to obtain the "wincoins" (internal cryptovalue).
Each user has his own wallet in which are contained the received revenew.
Furthermore, each registered user can execute the next 17 operations:

- Login: it allows the user to log in with his own credential to the service contents.
- Logout: it allows the user to end the session.
- List users: it allows the user to visualize a partial list of registered users to the service.
- List followers: it allows the user to visualize its own followers' list.
- List following: it allows the user to visualize the list of the users who follows him.
- Follow: it allows the user to follow another registered user with at least an equal tag.
- Unfollow: it allows the user to unfollow one of users he follows.
- Blog: it allows the user to recover his own blog.
- Post: it allows the user to create a new post.
- Feed: it allows the user to recover his own feed.
- Show post: it allows the user to see the content of a post in his own blog or feed.
- Delete: it allows the user to delete one of his own post.
- Rewin: it allows the user to put a post that is in his own feed on his own blog.
- Rate: it allows the user to rate, positively or negatively, a post on his own feed.
- Comment: it allows the user to comment on one of the users' posts of which he is a follower.
- Wallet: it allows the user to obtain the value of his own wallet and its relative transaction history.
- Wallet btc: it allows the user to obtain the value of his own wallet converted in bitcoin.
(* for more info about commands and their syntax and effects, we invite you to consult the project report in the pdf version *)

This CLI application was developed for the "Computer Network" course and treated the arguments discusses during the course.
Arguments treated in this project are: client-server structure, multithread (using threadpool), TCP socket, UDP multicast socket, JAVA I/O, RMI, RMI Callback, HTTP request (using JSON), JCF and others.
                                                        
#  Instructions for the execution                                                      
There's a report (in italian language) in the Code section.
In the report (at the section 4.1), there are all the instructions to execute Server e Client files and to interact with them using prompt.
There's a directory named "jar"; positioning inside that is possibile executing the CLI application 
putting on two different prompt the next two commmand:
  "java -jar Server.jar" & "java -jar Client.jar"
  
# WARNING
This project was developed for improve my java developing skills and to put in practice networks theory learned during the other section of the Computer Network course. In fact, it can contain some errors and some bad practices in developing a network java application.                        
