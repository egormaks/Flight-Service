package edu.uw.cs;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.*;


/**
 * Runs queries against a back-end database
 */
public class Query {
    // login info
    private boolean inUse;
    private String currUser;
    private Map<Integer, ArrayList<Flight>> itineraries = new HashMap<>();

    // DB Connection
    private Connection conn;
    private static final int MAX_COUNT = 10;
    // Password hashing parameter constants
    private static final int HASH_STRENGTH = 65536;
    private static final int KEY_LENGTH = 128;

    // Canned queries
    private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
    private static final String GET_DIRECT_FLIGHTS = "SELECT TOP (?) * " +
            "FROM Flights WHERE origin_city = ? AND dest_city = ? AND canceled = 0 AND day_of_month = ? " +
            "ORDER BY actual_time, fid;";
    private static final String GET_INDIRECT_FLIGHTS = "SELECT TOP (? - ?)" +
            "F.fid, F.carrier_id, " +
            "F.flight_num, F.dest_city, F.actual_time, F.capacity,\n" +
            "F.price, F2.fid, F2.carrier_id, F2.flight_num, F2.actual_time, F2.capacity, F2.price\n" +
            "FROM Flights AS F, Flights AS F2 \n" +
            "WHERE F.origin_city = ? AND F.dest_city = F2.origin_city AND F2.dest_city = ?\n" +
            "    AND F.canceled = F2.canceled AND F.canceled = 0 AND F.day_of_month = F2.day_of_month \n" +
            "    AND F.day_of_month = ?\n" +
            "ORDER BY (F.actual_time + F2.actual_time), F.fid, F2.fid";
    private static final String CHECK_DAY_AVAILABILITY = "SELECT res_id \n" +
            "FROM Reservations AS R, Itineraries AS I\n" +
            "WHERE R.i_id = I.id AND I.day = ? AND R.username = ?";
    private static final String GET_UNPAID_RESERVATIONS = "SELECT R.res_id, I.total_price\n" +
            "FROM Reservations AS R, Itineraries AS I\n" +
            "WHERE R.res_id = ? AND R.username = ? AND R.i_id = I.id AND R.is_paid = 0";
    private static final String CREATE_USER = "INSERT INTO Users(username, pass_hash, pass_salt, " +
            "balance) VALUES (?, ?, ?, ?)";
    private static final String GET_FLIGHT_DATA = "SELECT * FROM Flights WHERE fid = ?";
    private static final String GET_ITINERARY_DATA = "SELECT int_fid, dest_fid FROM Itineraries " +
            "WHERE id = ?";
    private static final String BEGIN_TRANSACTION_SQL = "BEGIN TRANSACTION;";
    private PreparedStatement beginTransactionStatement;
    private PreparedStatement checkFlightCapacityStatement;
    private PreparedStatement findDirect;
    private PreparedStatement findIndirect;
    private PreparedStatement checkDayAvailability;
    private PreparedStatement unpaidReservations;
    private PreparedStatement getFlight;
    private PreparedStatement createUser;
    private PreparedStatement getItineraryData;

    /**
     * Establishes a new application-to-database connection. Uses the
     * dbconn.properties configuration settings
     *
     * @throws IOException
     * @throws SQLException
     */
    public void openConnection() throws IOException, SQLException {
        // Connect to the database with the provided connection configuration
        Properties configProps = new Properties();
        configProps.load(new FileInputStream("dbconn.properties"));
        String serverURL = configProps.getProperty("hw1.server_url");
        String dbName = configProps.getProperty("hw1.database_name");
        String adminName = configProps.getProperty("hw1.username");
        String password = configProps.getProperty("hw1.password");
        String connectionUrl = String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
                dbName, adminName, password);
        conn = DriverManager.getConnection(connectionUrl);

        // By default, automatically commit after each statement
        conn.setAutoCommit(true);

        // By default, set the transaction isolation level to serializable
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    /**
     * Closes the application-to-database connection
     */
    public void closeConnection() throws SQLException {
        conn.close();
    }

    /**
     * Clear the data in any custom tables created.
     * <p>
     * WARNING! Do not drop any tables and do not clear the flights table.
     */
    public void clearTables() {
        try {
            PreparedStatement clear = conn.prepareStatement("DELETE FROM Users;\n" +
                    "DELETE FROM Reservations;\n" +
                    "DELETE FROM Itineraries; \n" +
                    "DBCC CHECKIDENT (Reservations, RESEED, 0);\n" +
                    "DBCC CHECKIDENT (Itineraries, RESEED, 0);");
            clear.executeUpdate();
            clear.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * prepare all the SQL statements in this method.
     */
    public void prepareStatements() throws SQLException {
        checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
        findDirect = conn.prepareStatement(GET_DIRECT_FLIGHTS);
        findIndirect = conn.prepareStatement(GET_INDIRECT_FLIGHTS);
        checkDayAvailability = conn.prepareStatement(CHECK_DAY_AVAILABILITY);
        unpaidReservations = conn.prepareStatement(GET_UNPAID_RESERVATIONS);
        getFlight = conn.prepareStatement(GET_FLIGHT_DATA);
        createUser = conn.prepareStatement(CREATE_USER);
        getItineraryData = conn.prepareStatement(GET_ITINERARY_DATA);
        beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION_SQL);
    }

    // private helper method for rolling back a transaction
    private void rollback() throws SQLException {
        conn.rollback();
        conn.setAutoCommit(true);
    }

    // private helper method for committing a transaction
    private void commit() throws SQLException {
        conn.commit();
        conn.setAutoCommit(true);
    }

    // starts transaction
    public void beginTransaction() throws SQLException {
		conn.setAutoCommit(false);
		beginTransactionStatement.executeUpdate();
	}
    /**
     * Takes a user's username and password and attempts to log the user in.
     *
     * @param username user's username
     * @param password user's password
     * @return If someone has already logged in, then return "User already logged
     * in\n" For all other errors, return "Login failed\n". Otherwise,
     * return "Logged in as [username]\n".
     */
    public String transaction_login(String username, String password) {
        if (inUse) return "User already logged in\n";
        byte[] pass_hash = null;
        byte[] pass_salt = null;
        for (int i = 0; i < MAX_COUNT; i++) { 
            try {
                beginTransaction();
                PreparedStatement info = conn.prepareStatement("SELECT * FROM Users WHERE username = ?");
                info.setString(1, username);
                ResultSet data = info.executeQuery();
                data.next();
                pass_hash = data.getBytes("pass_hash");
                pass_salt = data.getBytes("pass_salt");
                info.close();
                commit();
                KeySpec spec = new PBEKeySpec(password.toCharArray(), pass_salt, HASH_STRENGTH, KEY_LENGTH);
                SecretKeyFactory factory = null;
                byte[] hash = null;
                try {
                    factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                    hash = factory.generateSecret(spec).getEncoded();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
                    throw new IllegalStateException();
                }
                if (Arrays.equals(hash, pass_hash)) {
                    inUse = true;
                    currUser = username;
                    itineraries = new HashMap<>();
                    return "Logged in as " + username + "\n";
                } else {
                    return "Login failed\n";
                }
            } catch (SQLException ex) {
                try {
                    rollback();
                } catch (SQLException ex2) {
                    // unnable to complete
                }
            }
        }
        return "Login failed\n";
    }

    /**
     * Implement the create user function.
     *
     * @param username   new user's username. User names are unique the system.
     * @param password   new user's password.
     * @param initAmount initial amount to deposit into the user's account, should
     *                   be >= 0 (failure otherwise).
     * @return either "Created user {@code username}\n" or "Failed to create user\n"
     * if failed.
     */

    public String transaction_createCustomer(String username, String password, int initAmount) {
        if (initAmount < 0) return "Failed to create user\n";
        SecureRandom random = new SecureRandom();
        byte[] pass_salt = new byte[16];
        random.nextBytes(pass_salt);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), pass_salt, HASH_STRENGTH, KEY_LENGTH);
        SecretKeyFactory factory = null;
        byte[] pass_hash = null;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            pass_hash = factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException();
        }
        for (int i = 0; i < MAX_COUNT; i++) {
            try {
                beginTransaction();
                createUser.clearParameters();
                createUser.setString(1, username);
                createUser.setBytes(2, pass_hash);
                createUser.setBytes(3, pass_salt);
                createUser.setInt(4, initAmount);
                createUser.executeUpdate();
                commit();
                return "Created user " + username + "\n";
            } catch (SQLException ex) {
                try {
                    rollback();
                } catch (SQLException ex2) {
                    // unable to complete
                }
            }
        }
        return "Failed to create user\n";
    }

    /**
     * Implement the search function.
     * <p>
     * Searches for flights from the given origin city to the given destination
     * city, on the given day of the month. If {@code directFlight} is true, it only
     * searches for direct flights, otherwise is searches for direct flights and
     * flights with two "hops." Only searches for up to the number of itineraries
     * given by {@code numberOfItineraries}.
     * <p>
     * The results are sorted based on total flight time.
     *
     * @param originCity
     * @param destinationCity
     * @param directFlight        if true, then only search for direct flights,
     *                            otherwise include indirect flights as well
     * @param dayOfMonth
     * @param numberOfItineraries number of itineraries to return
     * @return If no itineraries were found, return "No flights match your
     * selection\n". If an error occurs, then return "Failed to search\n".
     * <p>
     * Otherwise, the sorted itineraries printed in the following format:
     * <p>
     * Itinerary [itinerary number]: [number of flights] flight(s), [total
     * flight time] minutes\n [first flight in itinerary]\n ... [last flight
     * in itinerary]\n
     * <p>
     * Each flight should be printed using the same format as in the
     * {@code Flight} class. Itinerary numbers in each search should always
     * start from 0 and increase by 1.
     * @see Flight#toString()
     */
    public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth,
                                     int numberOfItineraries) {
        StringBuffer sb = new StringBuffer();
        itineraries = new HashMap<>();
        Map<Compare, ArrayList<Flight>> flightsByTime = new TreeMap<>();
        int itineraryID = 0;
        try {
            findDirect.clearParameters();
            findDirect.setInt(1, numberOfItineraries);
            findDirect.setString(2, originCity);
            findDirect.setString(3, destinationCity);
            findDirect.setInt(4, dayOfMonth);
            ResultSet directFlights = findDirect.executeQuery();
            while (directFlights.next()) {
                int fid = directFlights.getInt("fid");
                String carrierId = directFlights.getString("carrier_id");
                String flightNum = directFlights.getString("flight_num");
                int time = directFlights.getInt("actual_time");
                int capacity = directFlights.getInt("capacity");
                int price = directFlights.getInt("price");
                Compare compare = new Compare(time, fid, 0);
                Flight flight = new Flight(fid, dayOfMonth, carrierId, flightNum, originCity,
                        destinationCity, time, capacity, price);
                flightsByTime.put(compare, new ArrayList<>());
                flightsByTime.get(compare).add(flight);
            }
            if (directFlight) {
                if (flightsByTime.size() == 0) return "No flights match your selection.\n";
                for (Compare compare : flightsByTime.keySet()) {
                    Flight flight = flightsByTime.get(compare).get(0);
                    sb.append("Itinerary " + itineraryID + ": 1 flight(s), " + flight.time + " minutes\n");
                    sb.append("ID: " + flight.fid + " Day: " + dayOfMonth + " Carrier: " + flight.carrierId + " Number: "
                            + flight.flightNum + " Origin: " + originCity + " Dest: "
                            + destinationCity + " Duration: " + flight.time
                            + " Capacity: " + flight.capacity + " Price: " + flight.price + "\n");
                    itineraries.put(itineraryID, flightsByTime.get(compare));
                    itineraryID++;
                }
            } else {
                int numDirect = flightsByTime.size();
                if (numDirect < numberOfItineraries) {
                    findIndirect.clearParameters();
                    findIndirect.setInt(1, numberOfItineraries);
                    findIndirect.setInt(2, numDirect);
                    findIndirect.setString(3, originCity);
                    findIndirect.setString(4, destinationCity);
                    findIndirect.setInt(5, dayOfMonth);
                    ResultSet results = findIndirect.executeQuery();
                    while (results.next()) {
                        int intFid = results.getInt(1);
                        String intCarrierId = results.getString(2);
                        String intFlightNum = results.getString(3);
                        String intCity = results.getString(4);
                        int intTime = results.getInt(5);
                        int intCapacity = results.getInt(6);
                        int intPrice = results.getInt(7);

                        int destFid = results.getInt(8);
                        String destCarrierId = results.getString(9);
                        String destFlightNum = results.getString(10);
                        int destTime = results.getInt(11);
                        int destCapacity = results.getInt(12);
                        int destPrice = results.getInt(13);

                        Flight toInt = new Flight(intFid, dayOfMonth, intCarrierId, intFlightNum,
                                originCity, intCity, intTime, intCapacity, intPrice);
                        Flight toDest = new Flight(destFid, dayOfMonth, destCarrierId, destFlightNum,
                                intCity, destinationCity, destTime, destCapacity, destPrice);

                        Compare compare = new Compare(toInt.time + toDest.time, toInt.fid, toDest.fid);
                        flightsByTime.put(compare, new ArrayList<>());
                        flightsByTime.get(compare).add(toInt);
                        flightsByTime.get(compare).add(toDest);
                    }
                }
                if (flightsByTime.size() == 0) return "No flights match your selection.\n";
                for (Compare compare : flightsByTime.keySet()) {
                    if (flightsByTime.get(compare).size() == 1) {
                        Flight flight = flightsByTime.get(compare).get(0);
                        sb.append("Itinerary " + itineraryID + ": 1 flight(s), " + flight.time + " minutes\n");
                        sb.append("ID: " + flight.fid + " Day: " + dayOfMonth + " Carrier: " + flight.carrierId + " Number: "
                                + flight.flightNum + " Origin: " + originCity + " Dest: "
                                + destinationCity + " Duration: " + flight.time
                                + " Capacity: " + flight.capacity + " Price: " + flight.price + "\n");
                    } else {
                        Flight toInt = flightsByTime.get(compare).get(0);
                        Flight toDest = flightsByTime.get(compare).get(1);
                        sb.append("Itinerary " + itineraryID + ": 2 flight(s), " + (toInt.time + toDest.time) + " minutes\n");
                        sb.append("ID: " + toInt.fid + " Day: " + dayOfMonth + " Carrier: " + toInt.carrierId + " Number: "
                                + toInt.flightNum + " Origin: " + originCity + " Dest: "
                                + toInt.destCity + " Duration: " + toInt.time
                                + " Capacity: " + toInt.capacity + " Price: " + toInt.price + "\n");
                        sb.append("ID: " + toDest.fid + " Day: " + dayOfMonth + " Carrier: " + toDest.carrierId + " Number: "
                                + toDest.flightNum + " Origin: " + toDest.originCity + " Dest: "
                                + destinationCity + " Duration: " + toDest.time
                                + " Capacity: " + toDest.capacity + " Price: " + toDest.price + "\n");
                    }
                    itineraries.put(itineraryID, flightsByTime.get(compare));
                    itineraryID++;
                }
            }
        } catch (SQLException ex) {
            return "Failed to search\n";
        }
        return sb.toString();
    }


    /**
     * Implements the book itinerary function.
     *
     * @param itineraryId ID of the itinerary to book. This must be one that is
     *                    returned by search in the current session.
     * @return If the user is not logged in, then return "Cannot book reservations,
     * not logged in\n". If try to book an itinerary with invalid ID, then
     * return "No such itinerary {@code itineraryId}\n". If the user already
     * has a reservation on the same day as the one that they are trying to
     * book now, then return "You cannot book two flights in the same
     * day\n". For all other errors, return "Booking failed\n".
     * <p>
     * And if booking succeeded, return "Booked flight(s), reservation ID:
     * [reservationId]\n" where reservationId is a unique number in the
     * reservation system that starts from 1 and increments by 1 each time a
     * successful reservation is made by any user in the system.
     */
    public String transaction_book(int itineraryId) {
        if (!inUse) return "Cannot book reservations, not logged in\n";
        if (!itineraries.containsKey(itineraryId)) return "No such itinerary " + itineraryId + "\n";
        for (int i = 0; i < MAX_COUNT; i++) { 
            try {
                ArrayList<Flight> itinerary = itineraries.get(itineraryId);
                int intFid = 0;
                int destFid;
                if (itinerary.size() == 1) {
                    if (itinerary.get(0).capacity == 0) {
                        return "Booking failed\n";
                    }
                    destFid = itinerary.get(0).fid;
                } else {
                    if (itinerary.get(0).capacity == 0 || itinerary.get(1).capacity == 0) {   
                        return "Booking failed\n";
                    }
                    intFid = itinerary.get(0).fid;
                    destFid = itinerary.get(1).fid;
                }
                beginTransaction();
                int day = itinerary.get(0).dayOfMonth;
                checkDayAvailability.clearParameters();
                checkDayAvailability.setInt(1, day);
                checkDayAvailability.setString(2, currUser);
                ResultSet checkDay = checkDayAvailability.executeQuery();
                if (checkDay.next()) {
                    commit();
                    return "You cannot book two flights in the same day\n";
                } else {
                    PreparedStatement checkItinerary;
                    if (itinerary.size() == 1) {
                        checkItinerary = conn.prepareStatement("SELECT * FROM Itineraries WHERE " +
                                "int_fid IS NULL AND dest_fid = ?");
                        checkItinerary.setInt(1, destFid);
                    } else {
                        checkItinerary = conn.prepareStatement("SELECT * FROM Itineraries WHERE " +
                                "int_fid = ? AND dest_fid = ?");
                        checkItinerary.setInt(1, intFid);
                        checkItinerary.setInt(2, destFid);
                    }
                    ResultSet checkExist = checkItinerary.executeQuery();
                    if (checkExist.next()) {
                        if (isFull(itinerary)) {
                            commit();
                            return "Booking failed\n";
                        }
                        PreparedStatement updateBookings;
                        if (itinerary.size() == 1) {
                            updateBookings = conn.prepareStatement("UPDATE Itineraries SET num_bookings_dest = " +
                                    "num_bookings_dest + 1 WHERE int_fid IS NULL AND dest_fid = ?");
                            updateBookings.setInt(1, destFid);
                        } else {
                            updateBookings = conn.prepareStatement("UPDATE Itineraries SET num_bookings_int = " +
                                    "num_bookings_int + 1, num_bookings_dest = num_bookings_dest + 1 WHERE " +
                                    "int_fid = ? AND dest_fid = ?");
                            updateBookings.setInt(1, intFid);
                            updateBookings.setInt(2, destFid);
                        }
                        updateBookings.executeUpdate();
                        updateBookings.close();
                    } else {
                        PreparedStatement createItinerary = conn.prepareStatement("INSERT INTO Itineraries VALUES " +
                                "(?, ?, ?, ?, ?, ?)");
                        if (itinerary.size() == 1) {
                            createItinerary.setNull(1, 4);
                            createItinerary.setInt(2, itinerary.get(0).fid);
                            createItinerary.setNull(3, 4);
                            createItinerary.setInt(5, itinerary.get(0).price);
                        } else {
                            createItinerary.setInt(1, itinerary.get(0).fid);
                            createItinerary.setInt(2, itinerary.get(1).fid);
                            createItinerary.setInt(3, 1);
                            createItinerary.setInt(5, itinerary.get(0).price + itinerary.get(1).price);
                        }
                        createItinerary.setInt(4, 1);
                        createItinerary.setInt(6, day);
                        createItinerary.executeUpdate();
                        createItinerary.close();
                    }
                    PreparedStatement getItineraryID = conn.prepareStatement("SELECT TOP 1 id FROM " +
                            "Itineraries ORDER BY id DESC");
                    ResultSet id = getItineraryID.executeQuery();
                    int i_id = 0;
                    if (id.next()) {
                        i_id = id.getInt("id");
                    }
                    PreparedStatement createReservation = conn.prepareStatement("INSERT INTO Reservations VALUES " +
                            "(? , ? , ?)");
                    createReservation.setString(1, currUser);
                    createReservation.setInt(2, 0);
                    createReservation.setInt(3, i_id);
                    createReservation.executeUpdate();
                    PreparedStatement getResID = conn.prepareStatement("SELECT res_id FROM Reservations WHERE " +
                            "username = ? AND i_id = ?");
                    getResID.setString(1, currUser);
                    getResID.setInt(2, i_id);
                    ResultSet rID = getResID.executeQuery();
                    int resID = 0;
                    if (rID.next()) {
                        resID = rID.getInt("res_id");
                    }
                    checkItinerary.close();
                    getItineraryID.close();
                    createReservation.close();
                    getResID.close();
                    commit();
                    return "Booked flight(s), reservation ID: " + resID + "\n";
                }
            } catch (SQLException ex) {
                try {
                    rollback();
                } catch (SQLException ex2) {
                    // unnable to complete
                }
            }
        }
        return "Booking failed\n";
    }

    /*
     * checks if an Itinerary is fully booked
     */
    private boolean isFull(ArrayList<Flight> itinerary) throws SQLException {
        PreparedStatement bookingCount;
        if (itinerary.size() == 1) {
            int fid = itinerary.get(0).fid;
            int flightCapacity = checkFlightCapacity(fid);
            bookingCount = conn.prepareStatement("SELECT * FROM Itineraries WHERE " +
                    "int_fid is NULL AND dest_fid = ?");
            bookingCount.setInt(1, fid);
            ResultSet results = bookingCount.executeQuery();
            int numBookings = 0;
            while (results.next()) {
                numBookings = results.getInt("num_bookings_dest");
            }
            bookingCount.close();
            return numBookings == flightCapacity;
        } else {
            int intFid = itinerary.get(0).fid;
            int destFid = itinerary.get(1).fid;
            int intFlightCapacity = checkFlightCapacity(intFid);
            int destFlightCapacity = checkFlightCapacity(destFid);
            bookingCount = conn.prepareStatement("SELECT * FROM Itineraries WHERE " +
                    "int_fid = ? AND dest_fid = ?");
            bookingCount.setInt(1, intFid);
            bookingCount.setInt(2, destFid);
            ResultSet results = bookingCount.executeQuery();
            int numIntBookings = 0;
            int numDestBookings = 0;
            while (results.next()) {
                numIntBookings = results.getInt(4);
                numDestBookings = results.getInt(5);
            }
            bookingCount.close();
            return numIntBookings == intFlightCapacity || numDestBookings == destFlightCapacity;
        }
    }

    /**
     * Implements the pay function.
     *
     * @param reservationId the reservation to pay for.
     * @return If no user has logged in, then return "Cannot pay, not logged in\n"
     * If the reservation is not found / not under the logged in user's
     * name, then return "Cannot find unpaid reservation [reservationId]
     * under user: [username]\n" If the user does not have enough money in
     * their account, then return "User has only [balance] in account but
     * itinerary costs [cost]\n" For all other errors, return "Failed to pay
     * for reservation [reservationId]\n"
     * <p>
     * If successful, return "Paid reservation: [reservationId] remaining
     * balance: [balance]\n" where [balance] is the remaining balance in the
     * user's account.
     */
    public String transaction_pay(int reservationId) {
        if (!inUse) return "Cannot pay, not logged in\n";
        for (int i = 0; i < MAX_COUNT; i++) { 
            try {
                beginTransaction();
                unpaidReservations.clearParameters();
                unpaidReservations.setInt(1, reservationId);
                unpaidReservations.setString(2, currUser);
                ResultSet unpaid = unpaidReservations.executeQuery();
                if (!unpaid.next()) {
                    commit();
                    return "Cannot find unpaid reservation " + reservationId + " under user: " +
                            currUser + "\n";
                } else {
                    int cost = 0;
                    cost = unpaid.getInt("total_price");
                    int balance = getBalance(currUser);
                    if (balance >= cost) {
                        int newBalance = balance - cost;
                        PreparedStatement updateBalance = conn.prepareStatement("UPDATE Users SET balance = ? WHERE " +
                                "username = ?");
                        PreparedStatement setPaid = conn.prepareStatement("UPDATE Reservations SET is_paid = 1 WHERE " +
                                "res_id = ?");
                        updateBalance.setInt(1, newBalance);
                        updateBalance.setString(2, currUser);
                        setPaid.setInt(1, reservationId);
                        updateBalance.executeUpdate();
                        setPaid.executeUpdate();
                        updateBalance.close();
                        setPaid.close();
                        commit();
                        return "Paid reservation: " + reservationId + " remaining balance: " + newBalance + "\n";
                    } else {
                        commit();
                        return "User has only " + balance + " in account but itinerary costs " + cost + "\n";
                    }
                }
            } catch (SQLException ex) {
                try {
                    rollback();
                } catch (SQLException ex2) {
                    // unnable to complete
                }
            }
        }
        return "Failed to pay for reservation\n";
    }

    /*
     * returns a user's total balance
     */
    private int getBalance(String username) throws SQLException {
        PreparedStatement balance = conn.prepareStatement("SELECT balance FROM Users WHERE username = ?");
        balance.setString(1, currUser);
        ResultSet amount = balance.executeQuery();
        amount.next();
        int totalBalance = amount.getInt("balance");
        balance.close();
        return totalBalance;
    }

    /**
     * Implements the reservations function.
     *
     * @return If no user has logged in, then return "Cannot view reservations, not
     * logged in\n" If the user has no reservations, then return "No
     * reservations found\n" For all other errors, return "Failed to
     * retrieve reservations\n"
     * <p>
     * Otherwise return the reservations in the following format:
     * <p>
     * Reservation [reservation ID] paid: [true or false]:\n" [flight 1
     * under the reservation] [flight 2 under the reservation] Reservation
     * [reservation ID] paid: [true or false]:\n" [flight 1 under the
     * reservation] [flight 2 under the reservation] ...
     * <p>
     * Each flight should be printed using the same format as in the
     * {@code Flight} class.
     * @see Flight#toString()
     */

    public String transaction_reservations() {
        if (!inUse) return "Cannot view reservations, not logged in\n";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < MAX_COUNT; i++) {    
            try {
                beginTransaction();
                PreparedStatement reservations = conn.prepareStatement("SELECT * FROM Reservations WHERE username = ?");
                reservations.setString(1, currUser);
                ResultSet results = reservations.executeQuery();
                while (results.next()) {
                    int resId = results.getInt("res_id");
                    int paid = results.getInt("is_paid");
                    int itineraryID = results.getInt("i_id");
                    sb.append("Reservation " + resId + " paid: " + (paid == 1) + ":\n");

                    getItineraryData.clearParameters();
                    getItineraryData.setInt(1, itineraryID);
                    ResultSet flightData = getItineraryData.executeQuery();
                    flightData.next();
                    int intFid = flightData.getInt("int_fid");
                    int destFid = flightData.getInt("dest_fid");
                    if (intFid == 0) {
                        Flight dFlight = getFlight(destFid);
                        sb.append("ID: " + dFlight.fid + " Day: " + dFlight.dayOfMonth + " Carrier: " + dFlight.carrierId
                                + " Number: " + dFlight.flightNum + " Origin: " + dFlight.originCity + " Dest: "
                                + dFlight.destCity + " Duration: " + dFlight.time + " Capacity: " + dFlight.capacity
                                + " Price: " + dFlight.price + "\n");
                    } else {
                        Flight intFlight = getFlight(intFid);
                        Flight destFlight = getFlight(destFid);
                        sb.append("ID: " + intFlight.fid + " Day: " + intFlight.dayOfMonth + " Carrier: " +
                                intFlight.carrierId + " Number: " + intFlight.flightNum + " Origin: " +
                                intFlight.originCity + " Dest: " + intFlight.destCity + " Duration: " + intFlight.time
                                + " Capacity: " + intFlight.capacity + " Price: " + intFlight.price + "\n");
                        sb.append("ID: " + destFlight.fid + " Day: " + destFlight.dayOfMonth + " Carrier: " +
                                destFlight.carrierId + " Number: " + destFlight.flightNum + " Origin: " +
                                destFlight.originCity + " Dest: " + destFlight.destCity + " Duration: " + destFlight.time
                                + " Capacity: " + destFlight.capacity + " Price: " + destFlight.price + "\n");
                    }
                }
                reservations.close();
                commit();
                if (sb.length() != 0) {
                    return sb.toString();
                } else return "No reservations found\n";
            } catch (SQLException ex) {
                try {
                    rollback();
                } catch (SQLException ex2) {
                    // unnable to complete
                }
            }
        }
        return "Failed to retrieve reservations\n";
    }

    /**
     * Returns a Flight object based on a given fid
     */
    private Flight getFlight(int fid) throws SQLException {
        getFlight.clearParameters();
        getFlight.setInt(1, fid);
        ResultSet flight = getFlight.executeQuery();
        flight.next();
        int dayOfMonth = flight.getInt("day_of_month");
        String carrierID = flight.getString("carrier_id");
        String flightNum = flight.getString("flight_num");
        String originCity = flight.getString("origin_city");
        String destCity = flight.getString("dest_city");
        int time = flight.getInt("actual_time");
        int capacity = flight.getInt("capacity");
        int price = flight.getInt("price");
        return new Flight(fid, dayOfMonth, carrierID, flightNum, originCity, destCity, time, capacity, price);
    }

    /**
     * Implements the cancel operation.
     *
     * @param reservationId the reservation ID to cancel
     * @return If no user has logged in, then return "Cannot cancel reservations,
     * not logged in\n" For all other errors, return "Failed to cancel
     * reservation [reservationId]\n"
     * <p>
     * If successful, return "Canceled reservation [reservationId]\n"
     * <p>
     * Even though a reservation has been canceled, its ID should not be
     * reused by the system.
     */
    public String transaction_cancel(int reservationId) {
        if (!inUse) return "Cannot cancel reservations, not logged in\n";
        for (int i = 0; i < MAX_COUNT; i++) {
            try {
                beginTransaction();
                PreparedStatement getRefund = conn.prepareStatement("SELECT I.id, I.total_price, I.int_fid, I.dest_fid " +
                        "FROM Reservations AS R, Itineraries AS I WHERE R.res_id = ? AND R.username = ? " +
                        "AND I.id = R.i_id");
                getRefund.setInt(1, reservationId);
                getRefund.setString(2, currUser);
                ResultSet refund = getRefund.executeQuery();
                if (!refund.next()) {
                    commit();
                    return "Failed to cancel reservation " + reservationId + "\n";
                }
                int price = refund.getInt("total_price");
                int ID = refund.getInt("id");
                int intFid = refund.getInt("int_fid");
                PreparedStatement updateBookings;
                if (intFid == 0) {
                    updateBookings = conn.prepareStatement("UPDATE Itineraries SET num_bookings_dest = num_bookings_dest " +
                            "- 1 WHERE id = ?");
                } else {
                    updateBookings = conn.prepareStatement("UPDATE Itineraries SET num_bookings_int = num_bookings_int - 1"
                            + ", num_bookings_dest = num_bookings_dest - 1 WHERE id = ?");
                }
                updateBookings.setInt(1, ID);
                updateBookings.executeUpdate();

                PreparedStatement pay = conn.prepareStatement("UPDATE Users SET balance = balance + ? WHERE username = ?");
                pay.setInt(1, price);
                pay.setString(2, currUser);
                pay.executeUpdate();

                PreparedStatement removeReservation = conn.prepareStatement("DELETE FROM Reservations WHERE res_id = ? ");
                removeReservation.setInt(1, reservationId);
                removeReservation.executeUpdate();

                updateBookings.close();
                pay.close();
                removeReservation.close();
                getRefund.close();
                
                commit();
                return "Canceled reservation " + reservationId + "\n";
            } catch (SQLException ex) {
                try {
                    rollback();
                } catch (SQLException ex2) {
                    // unnable to complete
                }
            }
        }
        return "Failed to cancel reservation\n";
    }

    /**
     * Example utility function that uses prepared statements
     */
    private int checkFlightCapacity(int fid) throws SQLException {
        checkFlightCapacityStatement.clearParameters();
        checkFlightCapacityStatement.setInt(1, fid);
        ResultSet results = checkFlightCapacityStatement.executeQuery();
        results.next();
        return results.getInt("capacity");
    }
}

/**
 * A class to store flight information.
 */
class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    public Flight(int fid, int dayOfMonth, String carrierId, String flightNum, String originCity,
                  String destCity, int time, int capacity, int price) {
        this.fid = fid;
        this.dayOfMonth = dayOfMonth;
        this.carrierId = carrierId;
        this.flightNum = flightNum;
        this.originCity = originCity;
        this.destCity = destCity;
        this.time = time;
        this.capacity = capacity;
        this.price = price;
    }

    @Override
    public String toString() {
        return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " + flightNum + " Origin: "
                + originCity + " Dest: " + destCity + " Duration: " + time + " Capacity: " + capacity + " Price: " + price;
    }
}

/*
 * Object used to compare Itineraries, sorts first by time, then by first fid, then second fid
 */
class Compare implements Comparable<Compare> {
    public int totalTime;
    public int fid;
    public int fid_2;

    public Compare(int totalTime, int fid, int fid_2) {
        this.totalTime = totalTime;
        this.fid = fid;
        this.fid_2 = fid_2;
    }

    public int compareTo(Compare other) {
        if (this.totalTime != other.totalTime) {
            return Integer.compare(this.totalTime, other.totalTime);
        } else if (this.fid != other.fid) {
            return Integer.compare(this.fid, other.fid);
        } else return Integer.compare(this.fid_2, other.fid_2);
    }
}


