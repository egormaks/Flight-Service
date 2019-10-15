CREATE TABLE Itineraries (
    id INTEGER PRIMARY KEY IDENTITY(1,1),
    int_fid INTEGER,
    dest_fid INTEGER,
    num_bookings_int INTEGER,
    num_bookings_dest INTEGER,
    total_price INTEGER,
    day INTEGER
);

CREATE TABLE Reservations (
    res_id INTEGER PRIMARY KEY IDENTITY(1,1),
    username VARCHAR(100),
    is_paid INTEGER,
    i_id INTEGER
);

INSERT INTO Reservations VALUES (1, 1, 1);
DELETE FROM Reservations;

CREATE TABLE Users (
    username VARCHAR(100) PRIMARY KEY,
    pass_hash VARBINARY(100),
    pass_salt VARBINARY(100),
    balance INTEGER
);