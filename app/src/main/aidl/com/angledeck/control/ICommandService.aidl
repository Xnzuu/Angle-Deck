package com.angledeck.control;

interface ICommandService {
    String run(String command);
    String startLog(int uid);
    String pollLog(int maxChars);
    void stopLog();
}
