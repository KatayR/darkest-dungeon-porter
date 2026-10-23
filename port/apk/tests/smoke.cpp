// libstdc++ smoke test for the bionic Box64 build: iostream, locale, exceptions, threads, fstream.
// Build (x86_64): x86_64-linux-gnu-g++ -O1 -o smoke-cpp smoke.cpp -pthread
#include <atomic>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <locale>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

int main() {
    bool ok = true;

    std::ostringstream os;
    os << 3.25 << ' ' << 42 << ' ' << std::hex << 255;
    std::cout << "stringstream: " << os.str() << '\n';
    ok &= os.str() == "3.25 42 ff";

    std::locale loc = std::locale::classic();
    std::cout << "locale: " << loc.name() << " toupper(a)=" << std::toupper('a', loc) << '\n';
    ok &= std::toupper('a', loc) == 'A';

    try {
        throw std::runtime_error("boom");
    } catch (const std::exception &e) {
        std::cout << "caught: " << e.what() << '\n';
    }

    std::atomic<int> n{0};
    std::mutex m;
    std::map<int, int> seen;
    std::vector<std::thread> ts;
    for (int i = 0; i < 4; i++)
        ts.emplace_back([&, i] {
            for (int j = 0; j < 10000; j++)
                n++;
            std::lock_guard<std::mutex> g(m);
            seen[i] = 1;
        });
    for (auto &t : ts)
        t.join();
    std::cout << "threads: n=" << n << " seen=" << seen.size() << '\n';
    ok &= n == 40000 && seen.size() == 4;

    {
        std::ofstream out("smoke-cpp.tmp");
        out << "line one\nline two\n";
    }
    std::ifstream in("smoke-cpp.tmp");
    std::string a, b;
    std::getline(in, a);
    std::getline(in, b);
    std::remove("smoke-cpp.tmp");
    std::cout << "fstream: " << a << " / " << b << '\n';
    ok &= b == "line two";

    std::cout << (ok ? "SMOKE-CPP OK" : "SMOKE-CPP FAIL") << std::endl;
    return 0;
}
