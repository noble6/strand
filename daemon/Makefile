# Host build for tests only; the device build is Android.bp.
CXXFLAGS ?= -O1 -g -Wall -Wextra -Werror -std=c++17
OBJS = util.o config.o actions.o engine.o main.o

all: strandd strandctl
strandd: $(OBJS)
	$(CXX) $(CXXFLAGS) -o $@ $(OBJS) -lpthread
strandctl: strandctl.cpp
	$(CXX) $(CXXFLAGS) -o $@ $<
%.o: %.cpp *.h
	$(CXX) $(CXXFLAGS) -c -o $@ $<
clean:
	rm -f *.o strandd strandctl
