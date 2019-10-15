# Flight-Service
This project is the amalgam of my CSE 344 : Database Management course. I worked on it throughout an entire quarter with two discreet stages. The first stage was implementing the involved database tables, writing SQL commands to upload/download the necessary data, and developing the client side Java code. The second stage involved implementing transaction behavior using the Java SQL package. 

This project acts as a flight service program. It allows for a user to create an account, search for different flights from a location to another (with 1 intermediate stop) within a specific month, "pay" for said flight, and cancel the reservation. I used a school issued Microsoft Azure license to set up and use the necessary database and encoded the client methods in Java. Java.sql allowed me to execute queries to this database as well as implement basic transactions. Although not encoded via JUnit testing, I wrote plain text test cases in lab2/cases that was used by graders to see if: a) my tests were encoded correctly and b) if my program ran correctly. 

There is an issue that must be address regarding this project. Given that the database used a class provided license to Microsoft Azure, since that class ended, I have since lost access to this database. This prevents the code from being functional as user data and flight data was stored in the database. Additionally, instead of using traditional JUnit testing, the class mandated that our testing consist of expected/actual outputs of the program. These are stored in txt files that graders run through some testing program I don't know about.

# Files that I worked on 

Downloads/lab2/src/main/java/edu/uw/cs/Query.java

The client code: in it you will find my various SQL queries used to access and write to my database. 

Downloads/lab2/cases/

This folder includes all the test cases I wrote for the program. This is formatted in calls to the client program.

# Technologies I used

Languages: 
Java, 
SQL

Techniques used: 
Password hashing and salt,
Database transactions

