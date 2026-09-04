#!/usr/bin/env python3

import subprocess
import atexit
import os
import signal
import sys
import time
import requests
import duckdb

commands_and_expected_output_strings = [
    # server version
    ("bin/start-uc-server -v", ["0."]),
    ("bin/start-uc-server --version", ["0."]),

    # REST/DuckDB cover catalog exploration; CLI was removed.

    # duckdb
    ("duckdb:install uc_catalog from core_nightly;", []),
    ("duckdb:load uc_catalog;", []),
    ("duckdb:install delta;", []),
    ("duckdb:load delta;", []),
    ("duckdb:CREATE SECRET (TYPE UC, TOKEN 'not-used', ENDPOINT 'http://127.0.0.1:8080', AWS_REGION 'us-east-2');", ["True"]),
    ("duckdb:ATTACH 'unity' AS unity (TYPE UC_CATALOG);", []),
    ("duckdb:SHOW ALL TABLES;", ["marksheet", "marksheet_uniform", "numbers", "as_int"]),
    ("duckdb:SELECT * FROM unity.default.numbers;", ["564"]),
]

def run_command_and_check_output(command, search_strings, duckdb_cursor=None):
    try:
        # Run the command and capture the output
        if duckdb_cursor:
            duckdb_cursor.execute(command)
            output = str(duckdb_cursor.fetchall())
        else:
            my_env = os.environ.copy()
            my_env["UC_OUTPUT_WIDTH"] = "190"
            result = subprocess.run(command, shell=True, check=True, text=True, capture_output=True, env=my_env)
            output = result.stdout
        search_results = {s: s in output for s in search_strings}
        return output, search_results
    except subprocess.CalledProcessError as e:
        print(f">> Command failed with error: {e.output} {command} {search_strings}")
        exit(1)


def start_server():
    print(f">> Starting server ...")
    log_file = "/tmp/server_log.txt"
    with open(log_file, 'w') as fp:
        process = subprocess.Popen("bin/start-uc-server", shell=True, stdout=fp, stderr=fp, preexec_fn=os.setsid)
        print(f">> Started server with PID {os.getpgid(process.pid)}")
        atexit.register(kill_process, process)
        return_code = process.poll()
        if return_code is not None:
            with open(log_file, 'r') as lf:
                print(f"Error starting process:\n{lf.read()}")
            sys.exit(1)
        print(f">> Waiting for server to accept connections ...")
        time.sleep(60)
        i = 0
        success = False
        while i < 60 and not success:
            try:
                response = requests.head("http://localhost:8081", timeout=60)
                if response.status_code == 200:
                    print("Server is running.")
                    success = True
                else:
                    print(f"Waiting... Server responded with status code: {response.status_code}")
                    time.sleep(2)
                    i += 1
            except requests.RequestException as e:
                print(f"Waiting... Failed to connect to the server: {e}")
                time.sleep(2)
                i += 1

        if i >= 60:
            with open(log_file, 'r') as lf:
                print(f">> Server is taking too long to get ready, failing tests. Log:\n{lf.read()}")
            exit(1)
    return process


def kill_process(process):
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        print(">> Stopped the server")
    except ProcessLookupError:
        # Process already terminated
        pass

if __name__ == "__main__":
    stdout, search_results = run_command_and_check_output("git status", ["h2db"])
    if True in [f for s, f in search_results.items()]:
        print()
        print(">> Cannot run this with uncommitted modifications in h2db")
        exit(1)
    server_process = start_server()
    duckdb_conn = duckdb.connect()
    duckdb_cursor = duckdb_conn.cursor()
    for command, expected_strings in commands_and_expected_output_strings:
        print(f">> Running client command: {command}")
        if command.startswith("duckdb:"):
            command = command[len('duckdb:'):]
            stdout, search_results = run_command_and_check_output(command, expected_strings, duckdb_cursor)
        else:
            stdout, search_results = run_command_and_check_output(command, expected_strings)
        print(stdout)
        for string, found in search_results.items():
            if not found:
                kill_process(server_process)
                print(f">> Output of '{command}' did not contain '{string}'")
                print(">> Output:\n" + stdout)
                print("FAILED: EXPECTED OUTPUT NOT FOUND")
                exit(1)
    kill_process(server_process)
    print("SUCCESS")


