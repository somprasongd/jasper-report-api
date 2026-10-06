-- Demo data for the sample report (samples/reports/demo/th_demo.jrxml). Loaded by the "dev" compose profile.
create table if not exists patient (hn varchar(20) primary key, name varchar(200));
create table if not exists visit (id int primary key, hn varchar(20));
insert into patient values ('HN001', 'สมชาย ใจดี') on conflict do nothing;
insert into visit values (1, 'HN001') on conflict do nothing;
insert into visit values (2, 'HN001') on conflict do nothing;
