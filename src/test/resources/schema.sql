create table if not exists patient (hn varchar(20) primary key, name varchar(200));
create table if not exists visit (id int primary key, hn varchar(20));
merge into patient key(hn) values ('HN001', 'สมชาย ใจดี');
merge into visit key(id) values (1, 'HN001');
merge into visit key(id) values (2, 'HN001');
